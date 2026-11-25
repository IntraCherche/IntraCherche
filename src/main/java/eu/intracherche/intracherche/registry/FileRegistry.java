package eu.intracherche.intracherche.registry;

import com.fasterxml.jackson.core.JsonProcessingException;
import eu.intracherche.intracherche.common.events.FileDiscoveredEvent;
import eu.intracherche.intracherche.common.indexing.IndexResolver;
import eu.intracherche.intracherche.common.indexing.SMBIndexResolver;
import eu.intracherche.intracherche.config.IndexesConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.sql.*;
import java.util.List;
import java.util.Set;

import static eu.intracherche.intracherche.common.constants.DatabaseConstants.FILE_REGISTRY_TABLE;

/**
 * This is the facade.
 */
public class FileRegistry {


    private final FileRegistryDao dao;
    private final Connection conn;
    private static final Logger log = LoggerFactory.getLogger(FileRegistry.class);

    private final IndexResolver smbResolver;

    public FileRegistry(Path dbPath, IndexesConfig indexesConfig) throws SQLException {
        RegistryDataSource ds = new RegistryDataSource(dbPath);
        this.conn = ds.connection();
        RegistrySchema.ensureSchema(conn);


        this.dao = new FileRegistryDao(conn);
        this.smbResolver = new SMBIndexResolver(indexesConfig);
    }

    public FileRegistryDao getDao() {
        return dao;
    }

    // THIS IS FOR TEST REMOVE THEN
    public Connection getConn(){
        return conn;
    }

    public long beginScan() {
        return dao.beginScan();
    }

    public void endScan(long scanId) throws SQLException {
        dao.endScan(scanId);

    }
    public void commit() throws SQLException {
        dao.commit();
    }
    public void registerScannedFile(
            FileDiscoveredEvent event, long scanId
    ) throws SQLException {
        List<String> indexMembershipList;
        // Compute the index membership of this scanned file
        // The indexMembershipList is determined by matching the file path against
        // configured indexed directories in the YAML configuration
        // This determines what indexes the file SHOULD be in based on its location
        if (event.isSmb()) {
            // For SMB, we pass the Share Name as the identifier
            // (Assuming you'll store the share name in your Event)
            indexMembershipList = smbResolver.resolveIndexNames(event.path(), event.smbShare());
        } else {
            indexMembershipList = List.of(); // only SMB sources exist
        }

        var fileRecord = FileRecord.discoveredWithIndexMembership(
                event.path(),
                event.lastModified(),
                event.size(),
                indexMembershipList,
                event.isSmb(),
                event.smbShare(),
                event.smbHost(), // Pass the SMB host information from the event
                event.allowSids(),
                event.denySids(),
                event.accessTokensHash()
        );
        log.debug("Registering scanned file: {} (mtime: {}, size: {})", 
            fileRecord.path(), fileRecord.mtime(), fileRecord.size());
        
        // Check if this file already exists in the registry
        try {
            boolean accessRightsChanged = dao.accessRightsChanged(fileRecord.path(), fileRecord.accessTokensHash());
            if (accessRightsChanged) {
                log.debug("Access rights changed for file: {} - triggering reindexing", fileRecord.path());
                // The DAO query will select it for reindexing because access_tokens_hash IS NULL or has changed
            }
        } catch (SQLException e) {
            log.warn("Failed to check access rights for file: {} - {}", fileRecord.path(), e.getMessage());
        }
        
        dao.upsert(fileRecord, scanId);
    }



    /**
     * Retrieves all file records from the registry database.

     * @return a list of FileRecord objects representing all files in the registry
     * @throws SQLException if a database access error occurs during query execution
     */
    public List<FileRecord> findAll() throws SQLException {
        return dao.findAll();
    }

    /**
     * Remove index membership for a specific core and delete orphaned entries.
     * This method removes the core from index_membership_actual for all files
     * that have it, and deletes files that no longer have any index memberships.
     *
     * @param coreName the name of the core to remove from index membership
     * @throws SQLException if a database access error occurs during query execution
     */
    public void removeIndexMembershipAndDeleteOrphans(String coreName) throws SQLException, JsonProcessingException {
        // Remove the index from all files in the registry
        dao.removeIndexFromAllFiles(coreName);
        // Then drop the files this left behind. Without it the rows stay in the registry
        // for good: no index covers them, yet findFilesToIndex() keeps returning them, so
        // every cycle retries files whose share may not even exist any more.
        int deleted = dao.deleteOrphanFiles();
        if (deleted > 0) {
            log.info("Deleted {} registry entry/entries left without any index after removing '{}'",
                    deleted, coreName);
        }
    }

    /** Number of registry rows belonging to no index. See {@link FileRegistryDao#countOrphanFiles()}. */
    public long countOrphanFiles() throws SQLException {
        return dao.countOrphanFiles();
    }

    /** Deletes registry rows belonging to no index; returns how many were removed. */
    public int deleteOrphanFiles() throws SQLException {
        return dao.deleteOrphanFiles();
    }

    /**
     * Queues again the files a pre-3.1.0 failure marked as indexed.
     * See {@link FileRegistryDao#requeueFailedFiles()}.
     */
    public int requeueFailedFiles() throws SQLException {
        return dao.requeueFailedFiles();
    }

    /** See {@link FileRegistryDao#setContentLimit}. */
    public synchronized void setContentLimit(String uuid, String limit) throws SQLException {
        dao.setContentLimit(uuid, limit);
    }

    /** See {@link FileRegistryDao#requeueLimitedFiles}. */
    public synchronized int requeueLimitedFiles(long maxFileSizeBytes, long maxTextChars) throws SQLException {
        return dao.requeueLimitedFiles(maxFileSizeBytes, maxTextChars);
    }

    /** See {@link FileRegistryDao#markAttemptStarted}. */
    public synchronized void markAttemptStarted(String uuid) throws SQLException { dao.markAttemptStarted(uuid); }

    /** See {@link FileRegistryDao#clearAttempt}. */
    public synchronized void clearAttempt(String uuid) throws SQLException { dao.clearAttempt(uuid); }

    /** See {@link FileRegistryDao#clearAllAttempts}. */
    public synchronized void clearAllAttempts() throws SQLException { dao.clearAllAttempts(); }

    /** See {@link FileRegistryDao#recordCrash}. */
    public synchronized void recordCrash(String uuid) throws SQLException { dao.recordCrash(uuid); }

    /** See {@link FileRegistryDao#recoverInterruptedAttempts}. */
    public synchronized List<String> recoverInterruptedAttempts() throws SQLException { return dao.recoverInterruptedAttempts(); }

    /** See {@link FileRegistryDao#crashCount}. */
    public synchronized int crashCount(String uuid) throws SQLException { return dao.crashCount(uuid); }

    /** See {@link FileRegistryDao#crashSuspectIds}. */
    public synchronized Set<String> crashSuspectIds() throws SQLException { return dao.crashSuspectIds(); }

    /** See {@link FileRegistryDao#requeueCrashLimitedFiles}. */
    public synchronized int requeueCrashLimitedFiles(long currentHeapMb) throws SQLException { return dao.requeueCrashLimitedFiles(currentHeapMb); }

    public synchronized void markIndexed(String uuid) throws SQLException {
        dao.markIndexed(uuid);
    }

    public synchronized void markParseFailed(String uuid) throws SQLException {
        dao.markParseFailed(uuid);
    }

    public synchronized void markFailed(String uuid) throws SQLException {
        dao.markFailed(uuid);
    }


    /**
     * Finds all files that need to be indexed.
     * 
     * Files are selected for indexing if:
     * - indexed_at IS NULL (never indexed)
     * - indexed_at < mtime (file modified after last indexing)
     * 
     * This method uses the database query in FileRegistryDao.findFilesToIndex()
     * which selects files based on the condition: indexed_at IS NULL OR indexed_at < mtime
     * 
     * The indexMembershipSolr and indexMembershipActual fields are used to determine
     * if a file's index membership has changed since last indexing, but the actual
     * re-indexing decision is based on modification time comparison.
     */
    public List<FileRecord> findFilesToIndex() throws SQLException {
        log.debug("Finding files to index...");
        List<FileRecord> result = dao.findFilesToIndex();
        log.debug("Found {} files to index", result.size());
        return result;
    }

    public long countFilesToIndex() throws SQLException {
        return dao.countFilesToIndex();
    }

    /**
     * Count all files in the registry.
     *
     * @return the number of files in the registry
     * @throws SQLException if a database access error occurs during query execution
     */
    public long countAllFiles() throws SQLException {
        return dao.countFiles();
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
     * @throws SQLException if a database access error during query execution
     */
    public long countStalledFiles(String shareName, long thresholdTimestamp) throws SQLException {
        return dao.countStalledFiles(shareName, thresholdTimestamp);
    }

    /**
     * Counts the number of files for a given SMB share.
     *
     * @param shareName the name of the SMB share to filter files by
     * @return the number of files in the specified SMB share
     * @throws SQLException if a database access error during query execution
     */
    public long countFilesByShare(String shareName) throws SQLException {
        return dao.countFilesByShare(shareName);
    }

    /**
     * Deletes stalled files from the registry for a given SMB share based on a timestamp threshold.
     *
     * A stalled file is defined as one that has not been seen (last_seen) after the
     * specified threshold timestamp. This method removes files from the registry
     * that meet this criteria.
     *
     * @param shareName the name of the SMB share to filter files by
     * @param threshold the timestamp threshold (in milliseconds) in the past before which a file is considered as stalled and should be deleted
     * @throws SQLException if a database access error during query execution
     */
    public void deleteStalledFiles(String shareName, long threshold) throws SQLException {
        dao.deleteStalledFiles(shareName, threshold);
    }

    /**
     * Retrieves stale Solr document IDs for a given SMB share based on a timestamp threshold.
     * 
     * A stale file is defined as one that has not been seen (last_seen) after the
     * specified threshold timestamp. This method returns the Solr document IDs of such files.
     * 
     * @param shareName the name of the SMB share to filter files by
     * @param threshold the timestamp threshold (in milliseconds) in the past before which a file is considered as stale
     * @param limit the maximum number of IDs to return
     * @param offset the offset for pagination
     * @return a list of Solr document IDs for stale files
     * @throws SQLException if a database access error during query execution
     */
    public List<String> getStalledSolrIds(String shareName, long threshold, int limit, int offset) throws SQLException {
        return dao.getStalledSolrIds(shareName, threshold, limit, offset);
    }





    // --- TEST METHODS

    /**
     * Test helper: returns true if any files have been recorded in the database.
     * Only used for tests that's why it is not production ready
     */
    public boolean hasScannedFiles() {
        String sql = "SELECT COUNT(*) FROM " + FILE_REGISTRY_TABLE;
        try (PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {

            if (rs.next()) {
                return rs.getInt(1) > 0;
            }
            return false;
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Test helper: clears all records from the database.
     * Only used for tests that's why it is not production ready
     */
    public void clearAllDatabaseRecords() throws SQLException {
        String sql = "DELETE FROM " + FILE_REGISTRY_TABLE;
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.executeUpdate();
        }
    }




}
