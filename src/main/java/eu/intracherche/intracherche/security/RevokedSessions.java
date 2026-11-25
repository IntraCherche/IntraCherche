package eu.intracherche.intracherche.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static eu.intracherche.intracherche.common.constants.DatabaseConstants.REVOKED_SESSIONS_TABLE;

/**
 * Session tokens ended before they expire: on logout, or when the account behind them may no
 * longer hold a session (see {@link SessionGuard}).
 *
 * <p>A session token is valid on its signature alone, so without this list "Se déconnecter" only
 * cleared the browser's cookie and a copy of the token kept working until it expired. Every
 * authenticated request is checked here, so the list is held in memory; it is also written to the
 * registry so that a restart does not bring revoked sessions back. An entry is only kept until the
 * token it names would have expired anyway, which bounds the list to the sessions ended within one
 * session lifetime.
 */
public class RevokedSessions {

    private static final Logger log = LoggerFactory.getLogger(RevokedSessions.class);

    private final Connection conn;
    private final Clock clock;
    /** jti → expiry of the token, in epoch milliseconds. */
    private final Map<String, Long> revoked = new ConcurrentHashMap<>();

    public RevokedSessions(Connection conn) throws SQLException {
        this(conn, Clock.systemUTC());
    }

    RevokedSessions(Connection conn, Clock clock) throws SQLException {
        this.conn = conn;
        this.clock = clock;
        load();
    }

    public boolean isRevoked(String jti) {
        Long expiresAt = revoked.get(jti);
        return expiresAt != null && expiresAt > clock.millis();
    }

    /** Ends the session {@code jti} now. Kept in memory even if the registry cannot be written. */
    public void revoke(String jti, String username, Instant expiresAt, String reason) {
        long now = clock.millis();
        revoked.put(jti, expiresAt.toEpochMilli());
        revoked.values().removeIf(expiry -> expiry <= now);
        synchronized (conn) {
            try {
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT OR REPLACE INTO " + REVOKED_SESSIONS_TABLE
                                + " (jti, username, expires_at, reason) VALUES (?, ?, ?, ?)")) {
                    ps.setString(1, jti);
                    ps.setString(2, username);
                    ps.setLong(3, expiresAt.toEpochMilli());
                    ps.setString(4, reason);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM " + REVOKED_SESSIONS_TABLE + " WHERE expires_at <= ?")) {
                    ps.setLong(1, now);
                    ps.executeUpdate();
                }
                conn.commit();
            } catch (SQLException e) {
                // The revocation still holds until the next restart; say so rather than fail the logout.
                log.error("Could not persist the revocation of a session of '{}': it will not survive a restart", username, e);
            }
        }
    }

    private void load() throws SQLException {
        synchronized (conn) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT jti, expires_at FROM " + REVOKED_SESSIONS_TABLE + " WHERE expires_at > ?")) {
                ps.setLong(1, clock.millis());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        revoked.put(rs.getString(1), rs.getLong(2));
                    }
                }
            }
        }
        log.info("{} revoked session(s) still within their lifetime", revoked.size());
    }
}
