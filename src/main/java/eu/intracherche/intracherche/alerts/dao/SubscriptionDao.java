package eu.intracherche.intracherche.alerts.dao;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.intracherche.intracherche.alerts.model.NotificationFrequency;
import eu.intracherche.intracherche.alerts.model.SearchSubscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static eu.intracherche.intracherche.common.constants.DatabaseConstants.SEARCH_SUBSCRIPTIONS_TABLE;

public class SubscriptionDao {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionDao.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Connection conn;

    public SubscriptionDao(Connection conn) {
        this.conn = conn;
    }

    public void save(SearchSubscription sub) throws SQLException {
        String sql = """
                INSERT INTO %s (id, username, user_email, query, indices, filename,
                    geo_city, geo_dpt, geo_region, geo_country,
                    date_offset_from, date_offset_to, sort, frequency,
                    last_result_hash, last_result_count, last_checked_at, created_at, is_active)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET
                    query = excluded.query,
                    indices = excluded.indices,
                    filename = excluded.filename,
                    geo_city = excluded.geo_city,
                    geo_dpt = excluded.geo_dpt,
                    geo_region = excluded.geo_region,
                    geo_country = excluded.geo_country,
                    date_offset_from = excluded.date_offset_from,
                    date_offset_to = excluded.date_offset_to,
                    sort = excluded.sort,
                    frequency = excluded.frequency,
                    last_result_hash = excluded.last_result_hash,
                    last_result_count = excluded.last_result_count,
                    last_checked_at = excluded.last_checked_at,
                    is_active = excluded.is_active
                """.formatted(SEARCH_SUBSCRIPTIONS_TABLE);

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, sub.id());
            ps.setString(2, sub.username());
            ps.setString(3, sub.userEmail());
            ps.setString(4, sub.query());
            ps.setString(5, toJson(sub.indices()));
            ps.setString(6, sub.filename());
            ps.setString(7, sub.geoCity());
            ps.setString(8, sub.geoDpt());
            ps.setString(9, sub.geoRegion());
            ps.setString(10, sub.geoCountry());
            if (sub.dateOffsetFrom() != null) ps.setInt(11, sub.dateOffsetFrom()); else ps.setNull(11, Types.INTEGER);
            if (sub.dateOffsetTo() != null) ps.setInt(12, sub.dateOffsetTo()); else ps.setNull(12, Types.INTEGER);
            ps.setString(13, sub.sort());
            ps.setString(14, sub.frequency().name());
            ps.setString(15, sub.lastResultHash());
            if (sub.lastResultCount() != null) ps.setInt(16, sub.lastResultCount()); else ps.setNull(16, Types.INTEGER);
            if (sub.lastCheckedAt() != null) ps.setLong(17, sub.lastCheckedAt()); else ps.setNull(17, Types.INTEGER);
            ps.setLong(18, sub.createdAt());
            ps.setInt(19, sub.isActive() ? 1 : 0);
            ps.executeUpdate();
        }
        conn.commit();
    }

    public List<SearchSubscription> findAllActive() throws SQLException {
        String sql = "SELECT * FROM " + SEARCH_SUBSCRIPTIONS_TABLE + " WHERE is_active = 1";
        return query(sql);
    }

    public List<SearchSubscription> findActiveByFrequency(NotificationFrequency freq) throws SQLException {
        String sql = "SELECT * FROM " + SEARCH_SUBSCRIPTIONS_TABLE +
                " WHERE is_active = 1 AND frequency = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, freq.name());
            return mapResultSet(ps.executeQuery());
        }
    }

    public List<SearchSubscription> findByUsername(String username) throws SQLException {
        String sql = "SELECT * FROM " + SEARCH_SUBSCRIPTIONS_TABLE +
                " WHERE username = ? ORDER BY created_at DESC";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            return mapResultSet(ps.executeQuery());
        }
    }

    public Optional<SearchSubscription> findById(String id) throws SQLException {
        String sql = "SELECT * FROM " + SEARCH_SUBSCRIPTIONS_TABLE + " WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            List<SearchSubscription> results = mapResultSet(ps.executeQuery());
            return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
        }
    }

    public void updateResultHash(String id, String hash, int count, long checkedAt) throws SQLException {
        String sql = "UPDATE " + SEARCH_SUBSCRIPTIONS_TABLE +
                " SET last_result_hash = ?, last_result_count = ?, last_checked_at = ? WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, hash);
            ps.setInt(2, count);
            ps.setLong(3, checkedAt);
            ps.setString(4, id);
            ps.executeUpdate();
        }
        conn.commit();
    }

    /** @return true if a row belonging to {@code username} was updated; false if {@code id} does
     *  not exist or belongs to someone else — the caller must not treat that as success. */
    public boolean updateFrequency(String id, String username, NotificationFrequency freq) throws SQLException {
        String sql = "UPDATE " + SEARCH_SUBSCRIPTIONS_TABLE +
                " SET frequency = ? WHERE id = ? AND username = ?";
        int rows;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, freq.name());
            ps.setString(2, id);
            ps.setString(3, username);
            rows = ps.executeUpdate();
        }
        conn.commit();
        return rows > 0;
    }

    public void delete(String id, String username) throws SQLException {
        String sql = "DELETE FROM " + SEARCH_SUBSCRIPTIONS_TABLE +
                " WHERE id = ? AND username = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            ps.setString(2, username);
            int rows = ps.executeUpdate();
            if (rows == 0) {
                throw new SQLException("Subscription not found or access denied: " + id);
            }
        }
        conn.commit();
    }

    private List<SearchSubscription> query(String sql) throws SQLException {
        try (Statement st = conn.createStatement()) {
            return mapResultSet(st.executeQuery(sql));
        }
    }

    private List<SearchSubscription> mapResultSet(ResultSet rs) throws SQLException {
        List<SearchSubscription> list = new ArrayList<>();
        while (rs.next()) {
            list.add(fromRow(rs));
        }
        return list;
    }

    private SearchSubscription fromRow(ResultSet rs) throws SQLException {
        String freqStr = rs.getString("frequency");
        NotificationFrequency freq = freqStr != null
                ? NotificationFrequency.valueOf(freqStr)
                : NotificationFrequency.IMMEDIATE;

        int rawOffsetFrom = rs.getInt("date_offset_from");
        Integer dateOffsetFrom = rs.wasNull() ? null : rawOffsetFrom;
        int rawOffsetTo = rs.getInt("date_offset_to");
        Integer dateOffsetTo = rs.wasNull() ? null : rawOffsetTo;

        int rawResultCount = rs.getInt("last_result_count");
        Integer lastResultCount = rs.wasNull() ? null : rawResultCount;
        long rawCheckedAt = rs.getLong("last_checked_at");
        Long lastCheckedAt = rs.wasNull() ? null : rawCheckedAt;

        return new SearchSubscription(
                rs.getString("id"),
                rs.getString("username"),
                rs.getString("user_email"),
                rs.getString("query"),
                fromJsonList(rs.getString("indices")),
                rs.getString("filename"),
                rs.getString("geo_city"),
                rs.getString("geo_dpt"),
                rs.getString("geo_region"),
                rs.getString("geo_country"),
                dateOffsetFrom,
                dateOffsetTo,
                rs.getString("sort"),
                freq,
                rs.getString("last_result_hash"),
                lastResultCount,
                lastCheckedAt,
                rs.getLong("created_at"),
                rs.getInt("is_active") == 1
        );
    }

    private String toJson(List<String> list) {
        try {
            return MAPPER.writeValueAsString(list);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize indices list", e);
        }
    }

    private List<String> fromJsonList(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return MAPPER.readValue(json, new TypeReference<List<String>>() {});
        } catch (IOException e) {
            log.warn("Failed to deserialize indices JSON '{}': {}", json, e.getMessage());
            return List.of();
        }
    }
}
