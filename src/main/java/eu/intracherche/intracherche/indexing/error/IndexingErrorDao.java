package eu.intracherche.intracherche.indexing.error;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static eu.intracherche.intracherche.common.constants.DatabaseConstants.INDEXING_ERRORS_TABLE;

public class IndexingErrorDao {

    private static final Logger log = LoggerFactory.getLogger(IndexingErrorDao.class);
    static final int MAX_ROWS = 5000;

    private final Connection conn;

    public IndexingErrorDao(Connection conn) {
        this.conn = conn;
    }

    public synchronized void insert(String kind, Instant timestamp, String path, String message, String coreName) {
        try {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO " + INDEXING_ERRORS_TABLE + " (occurred_at, kind, core_name, path, message) VALUES (?, ?, ?, ?, ?)")) {
                ps.setLong(1, timestamp.toEpochMilli());
                ps.setString(2, kind);
                ps.setString(3, coreName);
                ps.setString(4, path);
                ps.setString(5, message);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM " + INDEXING_ERRORS_TABLE + " WHERE id NOT IN (SELECT id FROM " + INDEXING_ERRORS_TABLE + " ORDER BY occurred_at DESC LIMIT ?)")) {
                ps.setInt(1, MAX_ROWS);
                ps.executeUpdate();
            }
            conn.commit();
        } catch (SQLException e) {
            log.warn("Failed to persist indexing error for {}: {}", path, e.getMessage());
        }
    }

    public synchronized void deleteByPath(String path) {
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM " + INDEXING_ERRORS_TABLE + " WHERE path = ?")) {
            ps.setString(1, path);
            ps.executeUpdate();
            conn.commit();
        } catch (SQLException e) {
            log.warn("Failed to clear indexing errors for {}: {}", path, e.getMessage());
        }
    }

    /**
     * Deletes the source-level row an index holds for a source. Scoped to the kind and
     * the index so that two indexes reading the same share keep a row each, and so that
     * a file whose path happens to equal the source label is never dropped with it.
     */
    public synchronized void deleteSourceRow(String path, String coreName) {
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM " + INDEXING_ERRORS_TABLE + " WHERE kind = 'source' AND path = ? AND core_name = ?")) {
            ps.setString(1, path);
            ps.setString(2, coreName);
            ps.executeUpdate();
            conn.commit();
        } catch (SQLException e) {
            log.warn("Failed to clear source error for {}: {}", path, e.getMessage());
        }
    }

    /**
     * Drops every source row except the ones listed. What a source row says is only
     * true of a source the configuration still names: correcting a share's address
     * would otherwise leave its old address in the panel's problem list for good,
     * since nothing ever reads that address again to clear it.
     */
    public synchronized void retainSourceRows(Collection<Source> keep) {
        StringBuilder sql = new StringBuilder("DELETE FROM " + INDEXING_ERRORS_TABLE + " WHERE kind = 'source'");
        for (int i = 0; i < keep.size(); i++) {
            sql.append(" AND NOT (core_name = ? AND path = ?)");
        }
        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            int param = 1;
            for (Source source : keep) {
                ps.setString(param++, source.coreName());
                ps.setString(param++, source.path());
            }
            ps.executeUpdate();
            conn.commit();
        } catch (SQLException e) {
            log.warn("Failed to drop source errors of sources no longer configured: {}", e.getMessage());
        }
    }

    public synchronized List<Row> findAll() {
        List<Row> result = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT occurred_at, kind, core_name, path, message FROM " + INDEXING_ERRORS_TABLE + " ORDER BY occurred_at DESC")) {
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                result.add(new Row(
                        Instant.ofEpochMilli(rs.getLong("occurred_at")),
                        rs.getString("kind"),
                        rs.getString("core_name"),
                        rs.getString("path"),
                        rs.getString("message")));
            }
        } catch (SQLException e) {
            log.warn("Failed to read indexing errors from DB: {}", e.getMessage());
        }
        return result;
    }

    /** An index and the source it reads, as {@link #retainSourceRows} matches them. */
    public record Source(String coreName, String path) {}

    public record Row(Instant timestamp, String kind, String coreName, String path, String message) {}
}
