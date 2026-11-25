package eu.intracherche.intracherche.alerts.dao;

import eu.intracherche.intracherche.alerts.model.AppNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

import static eu.intracherche.intracherche.common.constants.DatabaseConstants.NOTIFICATIONS_TABLE;

public class NotificationDao {

    private static final Logger log = LoggerFactory.getLogger(NotificationDao.class);

    private final Connection conn;

    public NotificationDao(Connection conn) {
        this.conn = conn;
    }

    public void save(AppNotification n) throws SQLException {
        String sql = """
                INSERT INTO %s (id, username, subscription_id, message, query_summary, created_at, is_read, notification_type)
                VALUES (?, ?, ?, ?, ?, ?, 0, ?)
                """.formatted(NOTIFICATIONS_TABLE);
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, n.id());
            ps.setString(2, n.username());
            ps.setString(3, n.subscriptionId());
            ps.setString(4, n.message());
            ps.setString(5, n.querySummary());
            ps.setLong(6, n.createdAt());
            ps.setString(7, n.notificationType() != null ? n.notificationType() : AppNotification.TYPE_SEARCH_ALERT);
            ps.executeUpdate();
        }
        conn.commit();
    }

    public List<AppNotification> findByUsername(String username, int limit, int offset) throws SQLException {
        String sql = "SELECT * FROM " + NOTIFICATIONS_TABLE +
                " WHERE username = ? ORDER BY created_at DESC LIMIT ? OFFSET ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            ps.setInt(2, limit);
            ps.setInt(3, offset);
            return mapResultSet(ps.executeQuery());
        }
    }

    public int countUnread(String username) throws SQLException {
        String sql = "SELECT COUNT(*) FROM " + NOTIFICATIONS_TABLE +
                " WHERE username = ? AND is_read = 0";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    public void markRead(String id, String username) throws SQLException {
        String sql = "UPDATE " + NOTIFICATIONS_TABLE +
                " SET is_read = 1 WHERE id = ? AND username = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            ps.setString(2, username);
            ps.executeUpdate();
        }
        conn.commit();
    }

    public void markAllRead(String username) throws SQLException {
        String sql = "UPDATE " + NOTIFICATIONS_TABLE +
                " SET is_read = 1 WHERE username = ? AND is_read = 0";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            ps.executeUpdate();
        }
        conn.commit();
    }

    public void markReadByType(String username, String notificationType) throws SQLException {
        String sql = "UPDATE " + NOTIFICATIONS_TABLE +
                " SET is_read = 1 WHERE username = ? AND notification_type = ? AND is_read = 0";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            ps.setString(2, notificationType);
            ps.executeUpdate();
        }
        conn.commit();
    }

    public boolean hasUnreadForSubscription(String subscriptionId) throws SQLException {
        String sql = "SELECT 1 FROM " + NOTIFICATIONS_TABLE +
                " WHERE subscription_id = ? AND is_read = 0 LIMIT 1";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, subscriptionId);
            return ps.executeQuery().next();
        }
    }

    public void pruneOld(String username, int keepCount) throws SQLException {
        String sql = """
                DELETE FROM %s WHERE username = ? AND id NOT IN (
                    SELECT id FROM %s WHERE username = ? ORDER BY created_at DESC LIMIT ?
                )
                """.formatted(NOTIFICATIONS_TABLE, NOTIFICATIONS_TABLE);
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            ps.setString(2, username);
            ps.setInt(3, keepCount);
            ps.executeUpdate();
        }
        conn.commit();
    }

    private List<AppNotification> mapResultSet(ResultSet rs) throws SQLException {
        List<AppNotification> list = new ArrayList<>();
        while (rs.next()) {
            String type = rs.getString("notification_type");
            list.add(new AppNotification(
                    rs.getString("id"),
                    rs.getString("username"),
                    rs.getString("subscription_id"),
                    rs.getString("message"),
                    rs.getString("query_summary"),
                    rs.getLong("created_at"),
                    rs.getInt("is_read") == 1,
                    type != null ? type : AppNotification.TYPE_SEARCH_ALERT
            ));
        }
        return list;
    }
}
