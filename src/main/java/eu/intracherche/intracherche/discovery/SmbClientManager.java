package eu.intracherche.intracherche.discovery;

import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.SmbConfig;
import com.hierynomus.smbj.auth.AuthenticationContext;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.DiskShare;
import eu.intracherche.intracherche.config.IndexedDirConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Component
public class SmbClientManager implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(SmbClientManager.class);

    // withTimeout: per-request SMB round-trip timeout.  Kept at 10 s so a broken
    // connection is detected quickly when we actually try to use it.
    //
    // soTimeout (TCP socket SO_TIMEOUT) is intentionally left at its SMBJ default
    // of 0 (infinite).  A non-zero soTimeout causes SMBJ's PacketReader background
    // thread to throw SocketTimeoutException after that many idle seconds, which
    // PacketReader treats as a FATAL transport error (calls handleError() and exits),
    // tearing down the session and closing all open DiskShares.  This is wrong during
    // long enrichment passes (NER + embeddings) that generate no SMB traffic.
    private static final SmbConfig SMB_CONFIG = SmbConfig.builder()
            .withTimeout(10, TimeUnit.SECONDS)
            .build();

    // smbj caches Connections inside the SMBClient (keyed by host) and reuses any whose
    // isConnected() still returns true. That flag stays true for a half-open socket the
    // server already dropped (idle timeout / reboot), so once a connection goes stale every
    // client.connect() hands back the dead one and authenticate() fails with "broken pipe"
    // indefinitely. The only reliable recovery is to discard the whole SMBClient (fresh
    // connectionTable), so the client is rebuilt from this factory instead of being created
    // once and held for the lifetime of the process.
    private final Supplier<SMBClient> clientFactory;
    private volatile SMBClient client;

    private final Map<String, IndexedDirConfig> configRegistry = new ConcurrentHashMap<>();
    private final Map<String, Session>    sessions    = new ConcurrentHashMap<>();  // key: server:user
    private final Map<String, Connection> connections = new ConcurrentHashMap<>();  // key: server host

    // Base back-off between session-open retries; package-private so tests can shrink it.
    volatile long baseRetryDelayMs = 2000L;

    public SmbClientManager() {
        this(() -> new SMBClient(SMB_CONFIG));
    }

    // Visible for testing: lets a test inject mock SMBClients to simulate a stale connection.
    SmbClientManager(Supplier<SMBClient> clientFactory) {
        this.clientFactory = clientFactory;
        this.client = clientFactory.get();
    }

    // Keyed on server AND share. The same share name can live on two servers — a share that
    // moved to a new address, or two file servers following one naming convention. Keyed on
    // the share name alone the last registered config won, so files on one server were read
    // with the other server's address: every read failed, and the files were marked failed
    // for a host they never belonged to.
    private static String configKey(String server, String shareName) {
        return server + "/" + shareName;
    }

    public void registerConfig(IndexedDirConfig config) {
        log.debug("Registering SMB config for share {} on server {}",
                config.getSmbShare(), config.getSmbServer());
        configRegistry.put(configKey(config.getSmbServer(), config.getSmbShare()), config);
    }

    public synchronized DiskShare getShare(String server, String shareName) throws IOException {
        IndexedDirConfig config = configRegistry.get(configKey(server, shareName));
        if (config == null) {
            throw new IllegalArgumentException(
                    "No SMB configuration registered for share '" + shareName + "' on server " + server);
        }

        String sessionKey = config.getSmbServer() + ":" + config.getSmbUser();

        Session session = sessions.get(sessionKey);
        if (session == null || !session.getConnection().isConnected()) {
            sessions.remove(sessionKey);
            session = openSessionWithRetry(config, sessionKey);
        }

        // isConnected() can return true for a half-open TCP socket that the server
        // already dropped (e.g. after an idle timeout during a long NER pass).
        // If connectShare fails, evict the stale session and retry once with a fresh one.
        //
        // SMBJ caches the DiskShare in its TreeConnectTable and returns the same object
        // on every connectShare() call.  If a background event (PacketReader soTimeout,
        // SmbjScanner try-with-resources) already closed that cached share, the returned
        // object has disconnected=true and the next openFile() would throw
        // "DiskShare has already been closed".  Detect this here and force a fresh
        // TREE_CONNECT before returning.
        try {
            DiskShare share = (DiskShare) session.connectShare(shareName);
            if (!share.isConnected()) {
                log.warn("SMB connectShare returned already-closed share for '{}'; forcing new tree connect", shareName);
                sessions.remove(sessionKey);
                session = openSessionWithRetry(config, sessionKey);
                share = (DiskShare) session.connectShare(shareName);
            }
            return share;
        } catch (Exception e) {
            log.warn("SMB connectShare failed on cached session (stale?), reconnecting: {}", e.getMessage());
            sessions.remove(sessionKey);
            return (DiskShare) openSessionWithRetry(config, sessionKey).connectShare(shareName);
        }
    }

    private Session openSessionWithRetry(IndexedDirConfig config, String sessionKey) throws IOException {
        int maxAttempts = 3;
        Exception lastException = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return openSession(config, sessionKey);
            } catch (Exception e) {
                lastException = e;
                if (attempt < maxAttempts) {
                    long delayMs = attempt * baseRetryDelayMs;
                    log.warn("SMB session open failed (attempt {}/{}), retrying in {}ms: {}",
                            attempt, maxAttempts, delayMs, e.getMessage());
                    // Rebuild the whole SMBClient rather than just closing the dead Connection:
                    // when the socket is half-open, closing it does not fire the clean-close
                    // event that evicts it from smbj's connectionTable, so the next
                    // client.connect() keeps handing back the same broken connection. A fresh
                    // SMBClient guarantees an empty connectionTable — the in-process equivalent
                    // of the service restart that was needed to recover.
                    recreateClient();
                    try {
                        Thread.sleep(delayMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Interrupted while retrying SMB session", ie);
                    }
                }
            }
        }
        throw new IOException("SMB session open failed after " + maxAttempts + " attempts for " + sessionKey, lastException);
    }

    private Session openSession(IndexedDirConfig config, String sessionKey) throws IOException {
        Connection conn = client.connect(config.getSmbServer());
        connections.put(config.getSmbServer(), conn);
        AuthenticationContext ac = new AuthenticationContext(
                config.getSmbUser(),
                config.getSmbPassword().toCharArray(),
                config.getSmbDomain()
        );
        Session session = conn.authenticate(ac);
        sessions.put(sessionKey, session);
        return session;
    }

    /**
     * Called when a request on a share handed out by {@link #getShare} failed at the transport
     * level (broken pipe, timeout, no SMB credits left). Rebuilds the client so the next
     * getShare() opens a fresh connection.
     * <p>
     * getShare() alone never notices this: connectShare() returns the DiskShare cached on the
     * session without a round trip, so a connection that died while the host slept looks
     * healthy there and only fails on openFile(). Sessions last the whole cycle, so every
     * remaining file of a 20k-file cycle failed on the same dead connection (Sept. 2026).
     * <p>
     * Several threads report the same breakage: only a report against the session still
     * cached rebuilds the client, so a late report does not discard the fresh one.
     */
    public synchronized void reportBrokenShare(String server, String shareName, DiskShare share) {
        IndexedDirConfig config = configRegistry.get(configKey(server, shareName));
        if (config == null) return;
        Session cached = sessions.get(config.getSmbServer() + ":" + config.getSmbUser());
        if (cached == null) return;
        Session broken;
        try {
            broken = share.getTreeConnect().getSession();
        } catch (Exception e) {
            broken = null;
        }
        if (broken != null && broken != cached) return;
        log.warn("SMB connection to {} is broken; rebuilding the SMB client", server);
        recreateClient();
    }

    /**
     * Closes the session for a specific share immediately after the DiskShare has been
     * released. Calling this right after source.close() lets smbj send a clean
     * SESSION_LOGOFF while the TCP connection is still alive, so PacketReader exits
     * normally instead of timing out and logging a TransportException WARN.
     * The session and connection are evicted so the next getShare() reconnects.
     */
    public synchronized void closeSessionForShare(String server, String shareName) {
        IndexedDirConfig config = configRegistry.get(configKey(server, shareName));
        if (config == null) return;
        String sessionKey = config.getSmbServer() + ":" + config.getSmbUser();
        Session session = sessions.remove(sessionKey);
        if (session == null) return;
        try {
            session.close();
        } catch (Exception e) {
            log.debug("SMB session close after share '{}' (non-fatal): {}", shareName, e.getMessage());
        }
        resetConnection(config.getSmbServer());
    }

    /**
     * Closes all cached sessions and connections gracefully while the TCP connection is
     * still alive, then clears both maps so fresh sessions are opened next cycle.
     * Call this at the end of each indexing cycle to avoid the server closing the
     * idle connection first (which causes smbj to log a timeout in PacketReader).
     */
    public synchronized void closeSessions() {
        if (sessions.isEmpty()) return;
        log.info("Closing {} active SMB session(s)", sessions.size());
        for (Map.Entry<String, Session> entry : sessions.entrySet()) {
            try {
                entry.getValue().close();
            } catch (Exception e) {
                log.warn("Error closing SMB session {}: {}", entry.getKey(), e.getMessage());
            }
        }
        sessions.clear();
        for (Map.Entry<String, Connection> entry : connections.entrySet()) {
            try {
                entry.getValue().close();
            } catch (Exception e) {
                log.warn("Error closing SMB connection {}: {}", entry.getKey(), e.getMessage());
            }
        }
        connections.clear();
    }

    /**
     * Discards the shared SMBClient and builds a fresh one with an empty connectionTable,
     * then closes the old one best-effort. This is the only reliable way to recover from a
     * wedged connection that smbj keeps reusing because its isConnected() flag is stale on a
     * half-open socket the server already dropped (the failure mode that previously required
     * a full service restart). Cached sessions/connections are cleared since they belonged to
     * the discarded client.
     */
    private synchronized void recreateClient() {
        SMBClient old = client;
        client = clientFactory.get();
        sessions.clear();
        connections.clear();
        if (old != null) {
            try {
                old.close();
            } catch (Exception e) {
                log.debug("Error closing stale SMB client (non-fatal): {}", e.getMessage());
            }
        }
    }

    // Closes the TCP connection for the given server host and evicts it from the cache.
    // The next client.connect() call will open a fresh socket rather than reusing a broken one.
    private void resetConnection(String serverHost) {
        Connection conn = connections.remove(serverHost);
        if (conn != null) {
            try { conn.close(); } catch (Exception ignored) {}
        }
    }

    @Override
    public void close() {
        log.info("Closing SMB Client and all active sessions...");
        closeSessions();
        client.close();
    }
}
