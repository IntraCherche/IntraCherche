package eu.intracherche.intracherche.discovery;

import com.hierynomus.protocol.transport.TransportException;
import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.auth.AuthenticationContext;
import com.hierynomus.smbj.common.SMBRuntimeException;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.DiskShare;
import com.hierynomus.smbj.share.TreeConnect;
import eu.intracherche.intracherche.config.IndexedDirConfig;
import eu.intracherche.intracherche.config.MountType;
import org.junit.jupiter.api.Test;

import java.net.SocketException;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression test for the "broken pipe forever" outage.
 *
 * smbj's SMBClient caches Connections internally (keyed by host) and reuses any whose
 * isConnected() returns true. When the SMB server silently drops an idle connection the
 * flag stays true (half-open socket), so every subsequent client.connect() returns the
 * dead connection and authenticate() fails with a "broken pipe" TransportException. Before
 * the fix, SmbClientManager only closed the individual (broken) Connection between retries,
 * which does not evict it from smbj's connectionTable, so the manager stayed wedged until
 * the whole process was restarted. The fix rebuilds the SMBClient on failure, which is what
 * a service restart did by hand.
 *
 * This test simulates a wedged client followed by a healthy one and asserts the manager
 * recovers on its own. It FAILS against the pre-fix code (manager keeps reusing the wedged
 * client and never reaches the healthy one) and PASSES once recovery rebuilds the client.
 */
class SmbClientManagerTest {

    private static final String SERVER = "198.51.100.21";
    private static final String SHARE  = "Demo_tuto_Youtube";
    private static final String USER   = "cs_indexer_ad";

    private static IndexedDirConfig smbConfig() {
        IndexedDirConfig cfg = new IndexedDirConfig();
        cfg.setMountType(MountType.SMB);
        cfg.setSmbServer(SERVER);
        cfg.setSmbShare(SHARE);
        cfg.setSmbUser(USER);
        cfg.setSmbPassword("Password123");
        cfg.setSmbDomain("");
        return cfg;
    }

    /** A client whose connection is half-open: connect() succeeds but authenticate() breaks. */
    private static SMBClient wedgedClient() throws Exception {
        SMBClient client = mock(SMBClient.class);
        Connection conn = mock(Connection.class);
        when(client.connect(SERVER)).thenReturn(conn);
        when(conn.authenticate(any(AuthenticationContext.class)))
                .thenThrow(new SMBRuntimeException(
                        new TransportException(new SocketException("Broken pipe"))));
        return client;
    }

    /** A fully working client that authenticates and returns a connected share. */
    private static SMBClient healthyClient(DiskShare share) throws Exception {
        SMBClient client = mock(SMBClient.class);
        Connection conn = mock(Connection.class);
        Session session = mock(Session.class);
        when(client.connect(SERVER)).thenReturn(conn);
        when(conn.authenticate(any(AuthenticationContext.class))).thenReturn(session);
        when(session.connectShare(SHARE)).thenReturn(share);
        return client;
    }

    @Test
    void recoversFromStaleCachedConnectionByRebuildingTheClient() throws Exception {
        SMBClient wedged = wedgedClient();
        DiskShare share = mock(DiskShare.class);
        when(share.isConnected()).thenReturn(true);
        SMBClient healthy = healthyClient(share);

        // The factory hands out the wedged client first (as if it had gone stale), then a
        // healthy one — exactly what a fresh SMBClient gives you after a restart.
        Queue<SMBClient> clients = new ArrayDeque<>();
        clients.add(wedged);
        clients.add(healthy);
        Supplier<SMBClient> factory = clients::poll;

        SmbClientManager manager = new SmbClientManager(factory);
        manager.baseRetryDelayMs = 1L; // keep the test fast
        manager.registerConfig(smbConfig());

        DiskShare result = manager.getShare(SERVER, SHARE);

        // Recovered: we got the share from the healthy client, and the wedged one was discarded.
        assertThat(result).isSameAs(share);
        verify(healthy).connect(SERVER);
        verify(wedged).close();
    }

    /** A healthy client whose share belongs to the given session, as smbj's TreeConnect says. */
    private static SMBClient clientWithShare(DiskShare share, Session session) throws Exception {
        SMBClient client = mock(SMBClient.class);
        Connection conn = mock(Connection.class);
        TreeConnect treeConnect = mock(TreeConnect.class);
        when(client.connect(SERVER)).thenReturn(conn);
        when(conn.authenticate(any(AuthenticationContext.class))).thenReturn(session);
        // Stays true after the host slept: smbj cannot tell the socket is gone.
        when(conn.isConnected()).thenReturn(true);
        when(session.getConnection()).thenReturn(conn);
        when(session.connectShare(SHARE)).thenReturn(share);
        when(share.isConnected()).thenReturn(true);
        when(share.getTreeConnect()).thenReturn(treeConnect);
        when(treeConnect.getSession()).thenReturn(session);
        return client;
    }

    /**
     * The laptop slept from midnight to 5 a.m. (Sept. 2026). On wake-up the session cached
     * before the sleep still looked connected: getShare() kept handing out its share without
     * a round trip, and every openFile() failed — "Relais brisé (pipe)", then "Not enough
     * credits". Session-open recovery never ran, so 2,000+ files failed on one dead connection.
     * <p>
     * Reproduces it: once openFile() breaks on the cached share, getShare() alone keeps
     * returning that same dead share; reporting the breakage makes the next call reconnect
     * with a fresh client.
     */
    @Test
    void reconnectsWhenACachedShareBreaksAfterTheHostSlept() throws Exception {
        DiskShare deadShare = mock(DiskShare.class);
        Session deadSession = mock(Session.class);
        SMBClient beforeSleep = clientWithShare(deadShare, deadSession);
        DiskShare freshShare = mock(DiskShare.class);
        SMBClient afterWakeUp = clientWithShare(freshShare, mock(Session.class));

        Queue<SMBClient> clients = new ArrayDeque<>(List.of(beforeSleep, afterWakeUp));
        SmbClientManager manager = new SmbClientManager(clients::poll);
        manager.baseRetryDelayMs = 1L;
        manager.registerConfig(smbConfig());

        assertThat(manager.getShare(SERVER, SHARE)).isSameAs(deadShare);
        // The wake-up: the cached share still claims to be connected, but the socket is gone.
        when(deadShare.openFile(any(), any(), any(), any(), any(), any()))
                .thenThrow(new SMBRuntimeException(
                        new TransportException(new SocketException("Relais brisé (pipe)"))));

        // The bug: nothing on the getShare() path notices, it serves the dead share again,
        // and every file opened on it fails.
        DiskShare served = manager.getShare(SERVER, SHARE);
        assertThat(served).isSameAs(deadShare);
        assertThatThrownBy(() -> new SmbContentSource(served, "00\\32\\Illustrirte Zeitung.pdf"))
                .hasMessageContaining("Relais brisé");

        manager.reportBrokenShare(SERVER, SHARE, deadShare);

        assertThat(manager.getShare(SERVER, SHARE)).isSameAs(freshShare);
        verify(beforeSleep).close();
    }

    /**
     * Four threads read from the share at once, so one breakage is reported up to four times,
     * some of them after the first report already rebuilt the client. Those late reports name
     * the dead session, not the cached one, and must leave the fresh client alone — otherwise
     * each rebuild would kill the reads just started on the one before.
     */
    @Test
    void aLateReportOnTheOldConnectionKeepsTheFreshOne() throws Exception {
        DiskShare deadShare = mock(DiskShare.class);
        SMBClient beforeSleep = clientWithShare(deadShare, mock(Session.class));
        DiskShare freshShare = mock(DiskShare.class);
        SMBClient afterWakeUp = clientWithShare(freshShare, mock(Session.class));

        Queue<SMBClient> clients = new ArrayDeque<>(List.of(beforeSleep, afterWakeUp));
        SmbClientManager manager = new SmbClientManager(clients::poll);
        manager.baseRetryDelayMs = 1L;
        manager.registerConfig(smbConfig());

        manager.getShare(SERVER, SHARE);
        manager.reportBrokenShare(SERVER, SHARE, deadShare);
        assertThat(manager.getShare(SERVER, SHARE)).isSameAs(freshShare);

        manager.reportBrokenShare(SERVER, SHARE, deadShare);   // a slower thread, same breakage

        assertThat(manager.getShare(SERVER, SHARE)).isSameAs(freshShare);
        verify(afterWakeUp, never()).close();
    }

    /**
     * Two servers can expose the same share name — typically when a share moves to a new
     * address and the old entry lingers. Keyed on the share name alone, the registry kept
     * one config for both, so files on one server were read with the other's address: every
     * read failed against a host those files never belonged to. Asking for a share on an
     * unregistered server must fail loudly instead of silently using the wrong server.
     */
    @Test
    void doesNotServeAShareFromAnotherServerWithTheSameShareName() throws Exception {
        DiskShare share = mock(DiskShare.class);
        when(share.isConnected()).thenReturn(true);
        SMBClient healthy = healthyClient(share);

        SmbClientManager manager = new SmbClientManager(() -> healthy);
        manager.baseRetryDelayMs = 1L;
        manager.registerConfig(smbConfig()); // SHARE, on SERVER only

        Exception caught = null;
        try {
            manager.getShare("192.0.2.21", SHARE);
        } catch (Exception e) {
            caught = e;
        }
        assertThat(caught)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("192.0.2.21");

        // The registered pair still resolves.
        assertThat(manager.getShare(SERVER, SHARE)).isSameAs(share);
    }
}
