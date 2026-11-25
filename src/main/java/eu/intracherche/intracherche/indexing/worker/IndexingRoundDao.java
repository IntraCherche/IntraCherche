package eu.intracherche.intracherche.indexing.worker;

import eu.intracherche.intracherche.indexing.worker.IndexingProgressTracker.IndexCycleSummary;
import eu.intracherche.intracherche.indexing.worker.IndexingProgressTracker.LastRound;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static eu.intracherche.intracherche.common.constants.DatabaseConstants.LAST_ROUND_INDEXES_TABLE;
import static eu.intracherche.intracherche.common.constants.DatabaseConstants.LAST_ROUND_TABLE;

/** Keeps the last indexing round that processed files, so the admin panel shows it after a restart. */
public class IndexingRoundDao {

    private static final Logger log = LoggerFactory.getLogger(IndexingRoundDao.class);

    private final Connection conn;

    public IndexingRoundDao(Connection conn) {
        this.conn = conn;
    }

    public synchronized void save(LastRound round) {
        try {
            try (Statement st = conn.createStatement()) {
                st.executeUpdate("DELETE FROM " + LAST_ROUND_TABLE);
                st.executeUpdate("DELETE FROM " + LAST_ROUND_INDEXES_TABLE);
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO " + LAST_ROUND_TABLE
                    + " (id, completed_at, duration_ms, files, text_pages, ocr_pages, ocr_active_ms) VALUES (1, ?, ?, ?, ?, ?, ?)")) {
                ps.setLong(1, round.completedAt().toEpochMilli());
                ps.setLong(2, round.durationMs());
                ps.setLong(3, round.files());
                ps.setLong(4, round.textPages());
                ps.setLong(5, round.ocrPages());
                ps.setLong(6, round.ocrActiveMs());
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO " + LAST_ROUND_INDEXES_TABLE
                    + " (core_name, succeeded, failed, skipped, text_pages, ocr_pages, duration_ms, ocr_active_ms)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
                for (Map.Entry<String, IndexCycleSummary> e : round.indexes().entrySet()) {
                    IndexCycleSummary s = e.getValue();
                    ps.setString(1, e.getKey());
                    ps.setInt(2, s.succeeded());
                    ps.setInt(3, s.failed());
                    ps.setInt(4, s.skipped());
                    ps.setLong(5, s.textPages());
                    ps.setLong(6, s.ocrPages());
                    ps.setLong(7, s.durationMs());
                    ps.setLong(8, s.ocrActiveMs());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            conn.commit();
        } catch (SQLException e) {
            log.warn("Failed to persist the last indexing round: {}", e.getMessage());
            try { conn.rollback(); } catch (SQLException ignored) { }
        }
    }

    public synchronized Optional<LastRound> load() {
        try (Statement st = conn.createStatement()) {
            ResultSet rs = st.executeQuery(
                    "SELECT completed_at, duration_ms, files, text_pages, ocr_pages, ocr_active_ms FROM " + LAST_ROUND_TABLE);
            if (!rs.next()) return Optional.empty();
            Instant completedAt = Instant.ofEpochMilli(rs.getLong("completed_at"));
            long durationMs  = rs.getLong("duration_ms");
            long files       = rs.getLong("files");
            long textPages   = rs.getLong("text_pages");
            long ocrPages    = rs.getLong("ocr_pages");
            long ocrActiveMs = rs.getLong("ocr_active_ms");

            Map<String, IndexCycleSummary> indexes = new LinkedHashMap<>();
            ResultSet ri = st.executeQuery(
                    "SELECT core_name, succeeded, failed, skipped, text_pages, ocr_pages, duration_ms, ocr_active_ms FROM "
                    + LAST_ROUND_INDEXES_TABLE + " ORDER BY core_name");
            while (ri.next()) {
                indexes.put(ri.getString("core_name"), new IndexCycleSummary(
                        ri.getInt("succeeded"), ri.getInt("failed"), ri.getInt("skipped"),
                        ri.getLong("text_pages"), ri.getLong("ocr_pages"),
                        ri.getLong("duration_ms"), ri.getLong("ocr_active_ms")));
            }
            return Optional.of(new LastRound(completedAt, durationMs, files, textPages, ocrPages, ocrActiveMs, indexes));
        } catch (SQLException e) {
            log.warn("Failed to read the last indexing round from DB: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
