package eu.intracherche.intracherche.registry;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.intracherche.intracherche.common.states.IndexingState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.*;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static eu.intracherche.intracherche.common.constants.DatabaseConstants.FILE_REGISTRY_TABLE;
import static eu.intracherche.intracherche.common.constants.DatabaseConstants.FILE_MEMBERSHIPS_TABLE;

/**
 * Pure SQL — no business logic
 *
 * This class handles database operations for FileRecord objects, including:
 * - Storing and retrieving file metadata
 * - Managing index membership information for files
 * - The indexMembershipSolr field tracks which SOLR indexes a file has been indexed into
 * - The indexMembershipActual field tracks which indexes a file should be in based on path configuration
 *
 * These fields are used to determine if a file needs re-indexing:
 * - If a file's modification time (mtime) is newer than its indexed_at timestamp, it will be re-indexed
 * - If a file's indexMembershipActual differs from indexMembershipSolr, it may need re-indexing
 * - Files are selected for indexing if indexed_at IS NULL OR indexed_at < mtime
 */
public class FileRegistryDao {
    private final Connection conn;
    private static final Logger log = LoggerFactory.getLogger(FileRegistry.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public FileRegistryDao(Connection conn) {
        this.conn = conn;
    }

    //   If excluded.indexed_at is NULL, use the table's current indexed_at
    private static final String UPSERT_FILE_SQL = """
                INSERT INTO %1$s (
                    id,
                    path,
                    mtime,
                    size,
                    indexed_at,
                    last_scan_id,
                    state,
                    last_seen,
                    is_smb,
                    smb_share_name,
                    smb_host,
                    index_membership_solr,
                    last_access_tokens_check,
                    access_tokens,
                    deny_tokens,
                    access_tokens_hash
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET
                    path = excluded.path,
                    mtime = excluded.mtime,
                    size = excluded.size,
                    indexed_at = COALESCE(excluded.indexed_at, %1$s.indexed_at),
                    last_scan_id = excluded.last_scan_id,
                    state = excluded.state,
                    last_seen = excluded.last_seen,
                    is_smb = excluded.is_smb,
                    smb_share_name = excluded.smb_share_name,
                    smb_host = excluded.smb_host,
                    index_membership_solr = excluded.index_membership_solr,
                    last_access_tokens_check = excluded.last_access_tokens_check,
                    access_tokens = excluded.access_tokens,
                    deny_tokens = excluded.deny_tokens,
                    access_tokens_hash = excluded.access_tokens_hash
            """.formatted(FILE_REGISTRY_TABLE);



    public List<FileRecord> findByState(IndexingState state) {
        String sql = """
                    SELECT id, path, mtime, size, indexed_at, state, last_seen, is_smb, smb_share_name, smb_host, index_membership_solr, last_access_tokens_check, access_tokens, deny_tokens, access_tokens_hash
                    FROM %s
                    WHERE state = ?
                    """.formatted(FILE_REGISTRY_TABLE);

        List<FileRecord> result = new ArrayList<>();

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, state.name());

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    List<String> indexMembershipSolr =
                            parseIndexMembership(rs.getString("index_membership_solr"));

                    // Get indexMembershipActual from the new file_memberships table
                    List<String> indexMembershipActual = getIndexMemberships(rs.getString("id"));

                    Set<String> accessTokens = parseAccessTokens(rs.getString("access_tokens"));
                    Set<String> denyTokens = parseAccessTokens(rs.getString("deny_tokens"));

                    // Handle SQLite JDBC quirk for nullable Long fields
                    Long indexedAt = rs.getLong("indexed_at");
                    if (rs.wasNull()) {
                        indexedAt = null;
                    }

                    // Handle SQLite JDBC quirk for nullable Long fields
                    Long lastAccessTokensCheck = rs.getLong("last_access_tokens_check");
                    if (rs.wasNull()) {
                        lastAccessTokensCheck = null;
                    }

                result.add(new FileRecord(
                        rs.getString("id"),
                        rs.getString("path"),
                        rs.getLong("mtime"),
                        rs.getLong("size"),
                        indexedAt,
                        IndexingState.valueOf(rs.getString("state")),
                        rs.getLong("last_seen"),
                        rs.getBoolean("is_smb"), // Read isSmb from database
                        rs.getString("smb_share_name"),
                        rs.getString("smb_host"), // Read smb_host from database
                        indexMembershipSolr,
                        indexMembershipActual,
                        lastAccessTokensCheck,
                        accessTokens,
                        denyTokens,
                        rs.getString("access_tokens_hash")
                        ));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to load files in state " + state, e);
        }

        return result;
    }

    /**
     * Find a file record by its path
     */
    public FileRecord findByPath(String path) throws SQLException {
        String sql = """
                SELECT id, path, mtime, size, indexed_at, state, last_seen, is_smb, smb_share_name, smb_host, index_membership_solr, last_access_tokens_check, access_tokens, deny_tokens, access_tokens_hash
                FROM %s
                WHERE path = ?
                """.formatted(FILE_REGISTRY_TABLE);

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, path);

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    List<String> indexMembershipSolr =
                            parseIndexMembership(rs.getString("index_membership_solr"));

                    // Get indexMembershipActual from the new file_memberships table
                    List<String> indexMembershipActual = getIndexMemberships(rs.getString("id"));

                    Set<String> accessTokens = parseAccessTokens(rs.getString("access_tokens"));
                    Set<String> denyTokens = parseAccessTokens(rs.getString("deny_tokens"));

                    // Handle SQLite JDBC quirk for nullable Long fields
                    Long indexedAt = rs.getLong("indexed_at");
                    if (rs.wasNull()) {
                        indexedAt = null;
                    }

                    // Handle SQLite JDBC quirk for nullable Long fields
                    Long lastAccessTokensCheck = rs.getLong("last_access_tokens_check");
                    if (rs.wasNull()) {
                        lastAccessTokensCheck = null;
                    }

                return new FileRecord(
                        rs.getString("id"),
                        rs.getString("path"),
                        rs.getLong("mtime"),
                        rs.getLong("size"),
                        indexedAt,
                        IndexingState.valueOf(rs.getString("state")),
                        rs.getLong("last_seen"),
                        rs.getBoolean("is_smb"), // Read isSmb from database
                        rs.getString("smb_share_name"),
                        rs.getString("smb_host"), // Read smb_host from database
                        indexMembershipSolr,
                        indexMembershipActual,
                        lastAccessTokensCheck,
                        accessTokens,
                        denyTokens,
                        rs.getString("access_tokens_hash")
                );
                }
            }
        }
        return null;
    }

    /**
     * Helper method to check if access rights have changed for a file
     * @param path the file path
     * @param newAccessTokensHash the new access tokens hash
     * @return true if access rights have changed, false otherwise
     */
    public boolean accessRightsChanged(String path, String newAccessTokensHash) throws SQLException {
        String sql = """
                SELECT access_tokens_hash
                FROM %s
                WHERE path = ?
                """.formatted(FILE_REGISTRY_TABLE);

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, path);

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String existingHash = rs.getString("access_tokens_hash");
                    // If there's no existing hash or it's different, access rights have changed
                    return existingHash == null || !existingHash.equals(newAccessTokensHash);
                }
            }
        }
        // If file doesn't exist in DB, treat as change (new file)
        return true;
    }

    public void upsert(FileRecord file, long scanId) throws SQLException {
        // For the new ID-based approach, we need to set the ID field properly
        // If the file doesn't have an ID yet, we'll generate it from getUniqueIdentifier()
        String id = file.id() != null ? file.id() : file.getUniqueIdentifier();

        try (PreparedStatement ps = conn.prepareStatement(UPSERT_FILE_SQL)) {
            ps.setString(1, id);
            ps.setString(2, file.path());
            ps.setLong(3, file.mtime());
            ps.setLong(4, file.size());

            if (file.indexedAt() != null) {
                ps.setLong(5, file.indexedAt());
            } else {
                // Bind NULL so the SQL logic can handle it
                ps.setNull(5, java.sql.Types.BIGINT);
            }

            ps.setLong(6, scanId);
            ps.setString(7, file.state().name());
            ps.setLong(8, file.lastSeen());
            ps.setBoolean(9, file.isSmb());
            ps.setString(10, file.smbShareName());
            ps.setString(11, file.smbHost()); // Add the smbHost parameter
            ps.setString(12, toJson(file.indexMembershipSolr()));
            // Note: index_membership_actual is now in the separate file_memberships table

            if (file.lastAccessTokensCheck() != null) {
                ps.setLong(13, file.lastAccessTokensCheck());
            } else {
                ps.setNull(13, Types.BIGINT);
            }

            // Set access_tokens, deny_tokens and access_tokens_hash fields
            ps.setString(14, toJson(file.accessTokens()));
            ps.setString(15, toJson(file.denyTokens()));
            ps.setString(16, file.accessTokensHash());


            ps.executeUpdate();
        }
        
        // Update the file_memberships table with the index membership actual values
        updateFileMemberships(id, file.indexMembershipActual());
    }

    public long countFiles() throws SQLException {
        try (PreparedStatement ps =
                     conn.prepareStatement("SELECT COUNT(*) FROM " + FILE_REGISTRY_TABLE);
             ResultSet rs = ps.executeQuery()) {

            rs.next();
            return rs.getLong(1);
        }
    }

    public long countFilesByShare(String shareName) throws SQLException {
        String sql = "SELECT COUNT(*) FROM " + FILE_REGISTRY_TABLE + " WHERE smb_share_name = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, shareName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        }
    }

    /**
     * Counts the number of stale files for a given SMB share based on a timestamp threshold.
     *
     * A stale file is defined as one that has not been seen (last_seen) after the
     * specified threshold timestamp. This method is used to identify files that
     * need cleanup operations (ie need to be removed from registry and index).
     *
     * @param shareName the name of the SMB share to filter files by
     * @param thresholdTimestamp the timestamp threshold (in milliseconds) in the past before which a file is considered as stalled and should be deleted
     * @return the number of files that are considered stale (have last_seen before threshold)
     * @throws RuntimeException if there is a database access error during query execution
     */
    public long countStalledFiles(String shareName, long thresholdTimestamp) {
        String sql = "SELECT COUNT(*) FROM " + FILE_REGISTRY_TABLE +
                " WHERE smb_share_name = ? AND last_seen < ?";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, shareName);
            ps.setLong(2, thresholdTimestamp);
            ResultSet rs = ps.executeQuery();
            return rs.next() ? rs.getLong(1) : 0;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to count stale files for share " + shareName, e);

        }
    }

    public long countByState(IndexingState state) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM " + FILE_REGISTRY_TABLE + " WHERE state = ?")) {
            ps.setString(1, state.name());
            ResultSet rs = ps.executeQuery();
            rs.next();
            return rs.getLong(1);
        }
    }

    public long countFilesToIndex() throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM " + FILE_REGISTRY_TABLE +
                " WHERE (indexed_at IS NULL OR indexed_at < mtime OR access_tokens_hash IS NULL)" +
                " AND " + NOT_KNOWN_UNPARSABLE);
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /**
     * Counts orphans: registry rows that belong to no index at all.
     * <p>
     * Membership is written by upsert() on every discovery, so a file scanned under a
     * configured share always has at least one row in the memberships table. Zero
     * memberships therefore means no configured index covers the file any more — its
     * index was deleted. Such rows are still selected by findFilesToIndex() and retried
     * every cycle, against a share nothing reads.
     * <p>
     * A share that is merely unreachable keeps its memberships (a missed scan writes
     * nothing), so temporarily offline files are never counted here.
     */
    public long countOrphanFiles() throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM " + FILE_REGISTRY_TABLE + " r WHERE NOT EXISTS (" +
                "SELECT 1 FROM " + FILE_MEMBERSHIPS_TABLE + " m WHERE m.file_id = r.id)");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /**
     * Puts back in the queue the files a pre-3.1.0 failure marked as indexed.
     * <p>
     * markFailed() used to set indexed_at alongside state='FAILED', which removed the file
     * from findFilesToIndex() for good: everything a mid-cycle outage touched was dropped
     * silently, and counted as indexed everywhere the panel looks. Clearing indexed_at for
     * exactly those rows queues them again; a file that is genuinely unreadable fails again
     * and stays visible as FAILED.
     * <p>
     * Only FAILED rows are touched. A successfully indexed file never carries that state:
     * markIndexed() sets indexed_at and leaves state alone.
     *
     * @return how many files were put back in the queue
     */
    public int requeueFailedFiles() throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE " + FILE_REGISTRY_TABLE +
                " SET indexed_at = NULL WHERE state = 'FAILED' AND indexed_at IS NOT NULL")) {
            int rows = ps.executeUpdate();
            conn.commit();
            return rows;
        }
    }

    /** Records which limit cut this file's content short, or clears it (null). */
    public void setContentLimit(String uuid, String limit) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE " + FILE_REGISTRY_TABLE + " SET content_limit = ? WHERE id = ?")) {
            ps.setString(1, limit);
            ps.setString(2, uuid);
            ps.executeUpdate();
        }
        conn.commit();
    }

    /**
     * Queues again every file a limit cut short, when that limit is now higher: indexed by name
     * only under a smaller maxFileSizeMb, or cut at a smaller maxTextChars. Their content is then
     * indexed in full (or up to the new limit) on the next cycle.
     *
     * @return how many files were put back in the queue
     */
    public int requeueLimitedFiles(long maxFileSizeBytes, long maxTextChars) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE " + FILE_REGISTRY_TABLE + " SET indexed_at = NULL, content_limit = NULL WHERE " +
                "(content_limit LIKE 'size:%' AND CAST(substr(content_limit, 6) AS INTEGER) < ?) OR " +
                "(content_limit LIKE 'chars:%' AND CAST(substr(content_limit, 7) AS INTEGER) < ?)")) {
            ps.setLong(1, maxFileSizeBytes);
            ps.setLong(2, maxTextChars);
            int rows = ps.executeUpdate();
            conn.commit();
            return rows;
        }
    }

    /** Deletes every orphan row (see {@link #countOrphanFiles()}); returns how many were removed. */
    public int deleteOrphanFiles() throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM " + FILE_REGISTRY_TABLE + " WHERE NOT EXISTS (" +
                "SELECT 1 FROM " + FILE_MEMBERSHIPS_TABLE + " m WHERE m.file_id = " +
                FILE_REGISTRY_TABLE + ".id)")) {
            int rows = ps.executeUpdate();
            conn.commit();
            return rows;
        }
    }

    /**
     * Returns the list of document ids in solr that are older than threshold (in millis)
     * It offers a limit and an offset to prevent the loading of a huge
     * list of ids that could cause an OOM.
     * @param shareName
     * @param threshold
     * @param limit
     * @param offset
     * @return
     * @throws SQLException
     */
    public List<String> getStalledSolrIds(String shareName, long threshold, int limit, int offset) throws SQLException {
        // This method is used to identify files that are stale and should be cleaned up
        // In the new implementation, we still need to return the registry IDs to be deleted from Solr
        // The FileRegistryDao doesn't have solr_doc_id anymore, so we'll return the registry IDs instead
        // which will be used to find the actual Solr documents to delete
        String sql = "SELECT id FROM " + FILE_REGISTRY_TABLE +
                " WHERE smb_share_name = ? AND last_seen < ? " +
                " ORDER BY path " +
                " LIMIT ? OFFSET ?";

        List<String> ids = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, shareName);
            ps.setLong(2, threshold);
            ps.setInt(3, limit);
            ps.setInt(4, offset);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String id = rs.getString("id");
                    if (id != null) ids.add(id);
                }
            }
        }
        return ids;
    }

    public void deleteStalledFiles(String shareName, long threshold) throws SQLException {
        String sql = "DELETE FROM " + FILE_REGISTRY_TABLE +
                " WHERE smb_share_name = ? AND last_seen < ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, shareName);
            ps.setLong(2, threshold);
            int rows = ps.executeUpdate();
            log.debug("Successfully deleted {} records from registry for share {}", rows, shareName);
        }
        conn.commit();
    }

    public long beginScan() {
        return System.currentTimeMillis();
    }

    public void endScan(long scanId) throws SQLException {
        // Nothing to update yet
        // last_scan_id already tells us whether a file was seen

        // No DB commit here
        // All writes already committed

        conn.commit();

    }

    public void commit() throws SQLException {
        conn.commit();
    }

    /** Clears indexed_at for every file so the indexer picks them all up again on the next cycle. */
    public int resetAllIndexedAt() throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE " + FILE_REGISTRY_TABLE + " SET indexed_at = NULL, parse_failed_sig = NULL, "
                        + "crash_count = 0, crash_sig = NULL, attempt_started_at = NULL")) {
            int rows = ps.executeUpdate();
            conn.commit();
            return rows;
        }
    }

    /**
     * Retrieves all file records from the registry database.
     * <p>
     * This method executes a SELECT query against the FILE_REGISTRY_TABLE table and maps
     * the results to a list of FileRecord objects. Each FileRecord contains the
     * file path, modification time, size, indexing timestamp, and Solr document ID.
     *
     * @return a list of FileRecord objects representing all files in the registry
     * @throws SQLException if a database access error occurs during query execution
     */
    public List<FileRecord> findAll() throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT  id,
                    path,
                    mtime,
                    size,
                    indexed_at,
                    last_scan_id,
                    state,
                    last_seen,
                    is_smb,
                    smb_share_name,
                    smb_host,
                    index_membership_solr,
                    last_access_tokens_check,
                    access_tokens,
                    deny_tokens,
                    access_tokens_hash
                FROM %s""".formatted(FILE_REGISTRY_TABLE)
        )) {
            ResultSet rs = ps.executeQuery();

            List<FileRecord> result = new ArrayList<>();
            while (rs.next()) {
                result.add(map(rs));
            }
            return result;
        }
    }

    public void markIndexed(String uuid) throws SQLException {
        log.debug("Attempting to mark file as indexed with uuid: {}", uuid);
        try (PreparedStatement ps = conn.prepareStatement("""
                    UPDATE %s
                    SET indexed_at = ?, parse_failed_sig = NULL, attempt_started_at = NULL
                    WHERE id = ?
                """.formatted(FILE_REGISTRY_TABLE))) {
            ps.setLong(1, System.currentTimeMillis());
            ps.setString(2, uuid);
            int rowsAffected = ps.executeUpdate();
            log.debug("Marked file as indexed. Rows affected: {}", rowsAffected);
        }
        conn.commit();
    }

    /**
     * Marks a file as failed WITHOUT touching indexed_at.
     * <p>
     * indexed_at means "this file is in Solr". A failure means it is not, so setting it
     * would drop the file from findFilesToIndex() for good: a share that went down
     * mid-cycle silently lost its files until someone reset the registry by hand. The
     * state column records the failure; the file stays pending and is retried next cycle.
     */
    public void markFailed(String uuid) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                    UPDATE %s
                    SET state = 'FAILED'
                    WHERE id = ?
                """.formatted(FILE_REGISTRY_TABLE))) {
            ps.setString(1, uuid);
            ps.executeUpdate();
        }
        conn.commit();
    }


    /**
     * Marks a file as failed because its content cannot be parsed, and remembers which version of
     * the file that was. Until its mtime or size changes it is not queued again: reading it again
     * gives the same result. indexed_at stays untouched, as in {@link #markFailed}.
     */
    public void markParseFailed(String uuid) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE " + FILE_REGISTRY_TABLE + " SET state = 'FAILED', parse_failed_sig = mtime || ':' || size WHERE id = ?")) {
            ps.setString(1, uuid);
            ps.executeUpdate();
        }
        conn.commit();
    }

    /** Notes that the file is about to be parsed. See {@link #recoverInterruptedAttempts}. */
    public void markAttemptStarted(String uuid) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE " + FILE_REGISTRY_TABLE + " SET attempt_started_at = ? WHERE id = ?")) {
            ps.setLong(1, System.currentTimeMillis());
            ps.setString(2, uuid);
            ps.executeUpdate();
        }
        conn.commit();
    }

    /** The parsing of the file ended, however it ended. */
    public void clearAttempt(String uuid) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE " + FILE_REGISTRY_TABLE + " SET attempt_started_at = NULL WHERE id = ? AND attempt_started_at IS NOT NULL")) {
            ps.setString(1, uuid);
            ps.executeUpdate();
        }
        conn.commit();
    }

    /** A clean shutdown interrupted the files in flight: that is not their fault. */
    public void clearAllAttempts() throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE " + FILE_REGISTRY_TABLE + " SET attempt_started_at = NULL WHERE attempt_started_at IS NOT NULL")) {
            ps.executeUpdate();
        }
        conn.commit();
    }

    /**
     * Counts one more crash against the file's current version (mtime and size): the count starts
     * again at 1 when the file has changed since the last crash. Also ends the file's attempt.
     */
    public void recordCrash(String uuid) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                UPDATE %s
                SET crash_count = CASE WHEN crash_sig = mtime || ':' || size THEN crash_count + 1 ELSE 1 END,
                    crash_sig = mtime || ':' || size,
                    attempt_started_at = NULL
                WHERE id = ?
                """.formatted(FILE_REGISTRY_TABLE))) {
            ps.setString(1, uuid);
            ps.executeUpdate();
        }
        conn.commit();
    }

    /**
     * Files whose parsing was under way when the application last died: counts a crash against
     * each of them and returns their ids. Several files are parsed at once, so some of them only
     * stood next to the one that exhausted the memory; they are told apart by parsing the suspects
     * one at a time (see {@link #crashSuspectIds}).
     */
    public List<String> recoverInterruptedAttempts() throws SQLException {
        List<String> ids = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id FROM " + FILE_REGISTRY_TABLE + " WHERE attempt_started_at IS NOT NULL");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) ids.add(rs.getString(1));
        }
        for (String id : ids) recordCrash(id);
        return ids;
    }

    /** How many times the file's current version has crashed the application. */
    public int crashCount(String uuid) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT crash_count FROM " + FILE_REGISTRY_TABLE + " WHERE id = ? AND crash_sig = mtime || ':' || size")) {
            ps.setString(1, uuid);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /**
     * Ids of the files that crashed the application at least once in their current version. They
     * stay parsed alone, even after a success: one that only fits when it has the heap to itself must
     * not be parsed beside others again, whether it was queued again for new access rights or a new index.
     */
    public Set<String> crashSuspectIds() throws SQLException {
        Set<String> ids = new HashSet<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id FROM " + FILE_REGISTRY_TABLE + " WHERE crash_count > 0 AND crash_sig = mtime || ':' || size");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) ids.add(rs.getString(1));
        }
        return ids;
    }

    /**
     * Queues again the files indexed by name only because they crashed the application, once the
     * heap is larger than it was when they were set aside (content_limit "crash:heap in MB"). A
     * file that crashes again is set aside again after the same number of attempts.
     */
    public int requeueCrashLimitedFiles(long currentHeapMb) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE " + FILE_REGISTRY_TABLE + " SET indexed_at = NULL, content_limit = NULL, crash_count = 0, crash_sig = NULL "
                        + "WHERE content_limit LIKE 'crash:%' AND CAST(substr(content_limit, 7) AS INTEGER) < ?")) {
            ps.setLong(1, currentHeapMb);
            int rows = ps.executeUpdate();
            conn.commit();
            return rows;
        }
    }

    /** SQL: the file is not one whose current version already failed to parse. */
    private static final String NOT_KNOWN_UNPARSABLE =
            "(parse_failed_sig IS NULL OR parse_failed_sig <> mtime || ':' || size)";

    public List<FileRecord> findFilesToIndex() throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT  id,
                    path,
                    mtime,
                    size,
                    indexed_at,
                    last_scan_id,
                    state,
                    last_seen,
                    is_smb,
                    smb_share_name,
                    smb_host,
                    index_membership_solr,
                    last_access_tokens_check,
                    access_tokens,
                    deny_tokens,
                    access_tokens_hash
                    FROM %s
                    WHERE (indexed_at IS NULL
                       OR indexed_at < mtime
                       OR access_tokens_hash IS NULL)
                      AND %s
                """.formatted(FILE_REGISTRY_TABLE, NOT_KNOWN_UNPARSABLE))) {

            ResultSet rs = ps.executeQuery();

            List<FileRecord> result = new ArrayList<>();
            while (rs.next()) {

                FileRecord record = map(rs);
                // Log which criteria caused the file to be selected for reindexing
                Long indexedAt = record.indexedAt();
                if (indexedAt == null) {
                    log.debug("File selected for reindexing (new file): {} (indexed_at is NULL)", record.path());
                } else if (indexedAt < record.mtime()) {
                    log.debug("File selected for reindexing (content or access rights changed): {} (indexed_at: {}, mtime: {})", 
                        record.path(), indexedAt, record.mtime());
                } else {
                    // This is when access rights have changed (access_tokens_hash IS NULL)
                        log.debug("File selected for reindexing (access rights changed): {} (access_tokens_hash is NULL)", record.path());

                }
                result.add(record);
            }
            return result;
        }
    }

    /**
     * Maps a ResultSet row to a FileRecord object.
     * <p>
     * This private helper method extracts data from the provided ResultSet and
     * constructs a FileRecord object with the following fields:
     * - path: the file system path
     * - mtime: the file modification time
     * - size: the file size in bytes
     * - indexed_at: the timestamp when the file was indexed (nullable)
     *
     * @param rs the ResultSet containing the database row data
     * @return a FileRecord object populated with data from the ResultSet
     * @throws SQLException if a database access error occurs while reading the result set
     */
    private FileRecord map(ResultSet rs) throws SQLException {


//        We cannot convert directly indexed_at field to Long because of
//        SQLite JDBC quirk. Explanation :
//        indexed_at is NULL for newly scanned files
//        SQLite JDBC sometimes returns NULL as a special internal type
//        The driver fails when coercing NULL → Long
        Long indexedAt = rs.getLong("indexed_at");

        if (rs.wasNull()) {
            indexedAt = null;
        }

        Long lastAccessTokensCheck = rs.getLong("last_access_tokens_check");
        if (rs.wasNull()) {
            lastAccessTokensCheck = null;
        }

        List<String> indexMembershipSolr =
                parseIndexMembership(rs.getString("index_membership_solr"));

        // Get indexMembershipActual from the new file_memberships table
        List<String> indexMembershipActual = getIndexMemberships(rs.getString("id"));

        Set<String> accessTokens = parseAccessTokens(rs.getString("access_tokens"));
        Set<String> denyTokens = parseAccessTokens(rs.getString("deny_tokens"));


        return new FileRecord(
                rs.getString("id"),
                rs.getString("path"),
                rs.getLong("mtime"),
                rs.getLong("size"),
                indexedAt,
                mapState(rs.getString("state")),
                rs.getLong("last_seen"),
                rs.getBoolean("is_smb"), // Read isSmb from database
                rs.getString("smb_share_name"),
                rs.getString("smb_host"), // Read smb_host from database
                indexMembershipSolr,
                indexMembershipActual,
                lastAccessTokensCheck,
                accessTokens,
                denyTokens,
                rs.getString("access_tokens_hash")
                );
    }
    
    /**
     * Gets the index memberships for a file by querying the file_memberships table.
     * 
     * @param fileId the ID of the file
     * @return a list of index names the file belongs to
     * @throws SQLException if a database access error occurs
     */
    private List<String> getIndexMemberships(String fileId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT index_name FROM " + FILE_MEMBERSHIPS_TABLE + " WHERE file_id = ? ORDER BY index_name")) {
            ps.setString(1, fileId);
            ResultSet rs = ps.executeQuery();
            
            List<String> memberships = new ArrayList<>();
            while (rs.next()) {
                memberships.add(rs.getString("index_name"));
            }
            return memberships;
        }
    }

    private IndexingState mapState(String state) {
        if (state == null) {
            return null;
        }
        try {
            return IndexingState.valueOf(state.toUpperCase());
        } catch (IllegalArgumentException e) {
            // Invalid state - log warning and return a default state
            log.warn("Invalid indexing state encountered: {}. Returning FAILED state.", state);
            return IndexingState.FAILED;
        }
    }

    private static List<String> parseIndexMembership(String json) throws SQLException {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return MAPPER.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (IOException e) {
            throw new SQLException("Invalid index membership JSON: " + json, e);
        }
    }

    private static Set<String> parseAccessTokens(String json) throws SQLException {
        if (json == null || json.isBlank()) {
            return Set.of();
        }
        try {
            return MAPPER.readValue(json, new TypeReference<Set<String>>() {
            });
        } catch (IOException e) {
            throw new SQLException("Invalid access tokens JSON: " + json, e);
        }
    }


    private static String toJson(Collection<String> values) throws SQLException {
        try {
            return MAPPER.writeValueAsString(values);
        } catch (JsonProcessingException e) {
            throw new SQLException("Failed to serialize index membership", e);
        }
    }
    
    /**
     * Updates the file_memberships table with the index membership actual values for a file.
     * This method first deletes all existing memberships for the file, then inserts the new ones.
     * 
     * @param fileId the ID of the file
     * @param indexNames the list of index names the file belongs to
     * @throws SQLException if a database access error occurs
     */
    private void updateFileMemberships(String fileId, List<String> indexNames) throws SQLException {
        // First, delete all existing memberships for this file
        try (PreparedStatement deletePs = conn.prepareStatement(
                "DELETE FROM " + FILE_MEMBERSHIPS_TABLE + " WHERE file_id = ?")) {
            deletePs.setString(1, fileId);
            deletePs.executeUpdate();
        }
        
        // Then insert the new memberships
        if (indexNames != null && !indexNames.isEmpty()) {
            try (PreparedStatement insertPs = conn.prepareStatement(
                    "INSERT OR IGNORE INTO " + FILE_MEMBERSHIPS_TABLE + " (file_id, index_name) VALUES (?, ?)")) {
                for (String indexName : indexNames) {
                    insertPs.setString(1, fileId);
                    insertPs.setString(2, indexName);
                    insertPs.addBatch();
                }
                insertPs.executeBatch();
            }
        }
    }
    
    /**
     * Removes a specific index membership from a file.
     * 
     * @param fileId the ID of the file
     * @param indexName the name of the index to remove
     * @throws SQLException if a database access error occurs
     */
    public void removeIndexFromFile(String fileId, String indexName) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM " + FILE_MEMBERSHIPS_TABLE + " WHERE file_id = ? AND index_name = ?")) {
            ps.setString(1, fileId);
            ps.setString(2, indexName);
            ps.executeUpdate();
        }
    }
    
    /**
     * Removes a specific index from all files in the registry.
     * This method is used to clean up index membership when an index is removed.
     * 
     * @param indexName the name of the index to remove from all files
     * @throws SQLException if a database access error occurs
     */
    public void removeIndexFromAllFiles(String indexName) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM " + FILE_MEMBERSHIPS_TABLE + " WHERE index_name = ?")) {
            ps.setString(1, indexName);
            ps.executeUpdate();
        }
        conn.commit();
    }

}
