package eu.intracherche.intracherche.sherlock;

import eu.intracherche.intracherche.config.LlmConfig;
import eu.intracherche.intracherche.config.LlmConfig.Tier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static eu.intracherche.intracherche.common.constants.DatabaseConstants.SHERLOCK_USAGE_TABLE;

/**
 * Questions each user may ask Sherlock per day: {@code llm.quota}, in three tiers read from the
 * user's AD groups. A day is the server's calendar day, so every count starts again at midnight.
 *
 * <p>Every kind of question counts alike — a new one, a follow-up, "Interroger ce document" — and
 * questions are counted even where the quota is unlimited, so the admin panel shows who uses
 * Sherlock before any limit is set. The count lives in its own table: deleting a question does not
 * give it back. A question that fails is given back, since the failure is the server's.
 */
public class SherlockQuota {

    private static final Logger log = LoggerFactory.getLogger(SherlockQuota.class);
    /** Days of counts kept, for nothing but a look back from the database. */
    private static final int DAYS_KEPT = 90;

    private final Connection conn;
    private final LlmConfig config;
    private final Clock clock;
    private final ZoneId zone;
    private volatile LocalDate prunedOn;

    public SherlockQuota(Connection conn, LlmConfig config) {
        this(conn, config, Clock.systemDefaultZone());
    }

    SherlockQuota(Connection conn, LlmConfig config, Clock clock) {
        this.conn = conn;
        this.config = config;
        this.clock = clock;
        this.zone = clock.getZone();
    }

    /** A user's day so far. {@code limit} 0 = unlimited. */
    public record Usage(Tier tier, int used, int limit) {
        public boolean exhausted() { return limit > 0 && used >= limit; }
    }

    /** One line of the admin panel: a user who asked something today. */
    public record UserUsage(String username, Tier tier, int used, int limit) {}

    /** The day so far for the user whose groups are {@code sids}. */
    public Usage usage(String username, Collection<String> sids) throws SQLException {
        Tier tier = config.getQuota().tierOf(sids);
        synchronized (conn) {
            return new Usage(tier, count(username, today()), config.getQuota().limitOf(tier));
        }
    }

    /**
     * Counts one question when the day's quota allows it. Checked and counted in one statement, so
     * two questions sent at once cannot both pass the last free place.
     *
     * @return the day with this question counted, or, when refused, as it stood
     */
    public Consumption consume(String username, Collection<String> sids) throws SQLException {
        Tier tier = config.getQuota().tierOf(sids);
        int limit = config.getQuota().limitOf(tier);
        String day = today();
        synchronized (conn) {
            prune();
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO " + SHERLOCK_USAGE_TABLE + " (username, day, count, tier) VALUES (?, ?, 0, ?) "
                            + "ON CONFLICT (username, day) DO UPDATE SET tier = excluded.tier")) {
                ps.setString(1, username);
                ps.setString(2, day);
                ps.setString(3, tier.name());
                ps.executeUpdate();
            }
            int rows;
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE " + SHERLOCK_USAGE_TABLE + " SET count = count + 1 "
                            + "WHERE username = ? AND day = ? AND (? = 0 OR count < ?)")) {
                ps.setString(1, username);
                ps.setString(2, day);
                ps.setInt(3, limit);
                ps.setInt(4, limit);
                rows = ps.executeUpdate();
            }
            int used = count(username, day);
            conn.commit();
            return new Consumption(rows == 1, new Usage(tier, used, limit));
        }
    }

    public record Consumption(boolean accepted, Usage usage) {}

    /** Gives back a question asked at {@code askedAtMillis}, on the day it was counted. */
    public void refund(String username, long askedAtMillis) {
        String day = LocalDate.ofInstant(Instant.ofEpochMilli(askedAtMillis), zone).toString();
        synchronized (conn) {
            try {
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE " + SHERLOCK_USAGE_TABLE + " SET count = count - 1 "
                                + "WHERE username = ? AND day = ? AND count > 0")) {
                    ps.setString(1, username);
                    ps.setString(2, day);
                    ps.executeUpdate();
                }
                conn.commit();
            } catch (SQLException e) {
                log.warn("Could not give back a failed Sherlock question to '{}': {}", username, e.getMessage());
            }
        }
    }

    /** Everyone who asked something today, the most questions first. */
    public List<UserUsage> today(int max) throws SQLException {
        List<UserUsage> users = new ArrayList<>();
        synchronized (conn) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT username, count, tier FROM " + SHERLOCK_USAGE_TABLE
                            + " WHERE day = ? AND count > 0 ORDER BY count DESC, username LIMIT ?")) {
                ps.setString(1, today());
                ps.setInt(2, max);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Tier tier = Tier.valueOf(rs.getString("tier"));
                        users.add(new UserUsage(rs.getString("username"), tier, rs.getInt("count"),
                                config.getQuota().limitOf(tier)));
                    }
                }
            }
        }
        return users;
    }

    private String today() {
        return LocalDate.now(clock).toString();
    }

    private int count(String username, String day) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT count FROM " + SHERLOCK_USAGE_TABLE + " WHERE username = ? AND day = ?")) {
            ps.setString(1, username);
            ps.setString(2, day);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /** Drops counts older than {@link #DAYS_KEPT}, once a day. Called holding the connection. */
    private void prune() throws SQLException {
        LocalDate now = LocalDate.now(clock);
        if (now.equals(prunedOn)) return;
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM " + SHERLOCK_USAGE_TABLE + " WHERE day < ?")) {
            ps.setString(1, now.minusDays(DAYS_KEPT).toString());
            ps.executeUpdate();
        }
        prunedOn = now;
    }
}
