package eu.intracherche.intracherche.registry;

import eu.intracherche.intracherche.common.states.IndexingState;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/**
 * Record representing a file in the registry.
 * 
 * This record contains metadata about files discovered by the system, including
 * their indexing status and membership in various indexes.
 */
public record FileRecord(
        String id,
        String path, // The relative path within the share
        long mtime,
        long size,
        // indexedAt field tells whether this file has already been successfully indexed for its current content?
        // “Is Solr up to date with respect to the filesystem?”
        // Eg : mtime > indexed_at → needs reindexing
        Long indexedAt,
        IndexingState state,
        long lastSeen,
        boolean isSmb,
        String smbShareName, // The actual share name (e.g., "Accounting")
        String smbHost, // The SMB server host name
        
        /**
         * The SOLR indexes that this file has been indexed into.
         * This field tracks the actual indexes where the file content has been stored in Solr.
         * It's updated during the indexing process when a file is successfully indexed.
         */
        List<String> indexMembershipSolr,
        
        /**
         * The actual indexes that this file belongs to based on the YAML index configuration.
         * This field is populated during file discovery by matching the file path against
         * configured indexed directories. It determines which indexes should contain this file.
         * 
         * The key difference between this and indexMembershipSolr is:
         * - indexMembershipActual: What indexes the file SHOULD be in (based on path configuration)
         * - indexMembershipSolr: What indexes the file HAS BEEN indexed into (based on actual indexing operations)
         */
        List<String> indexMembershipActual,
        Long lastAccessTokensCheck,
        Set<String> accessTokens,
        /** SIDs with an explicit Deny ACE (Windows/SMB only; always empty for POSIX). A user's
         *  SID being in this set overrides that same SID matching {@link #accessTokens}. */
        Set<String> denyTokens,
        String accessTokensHash
) {

    /**
     * Constructs a FileRecord with non nullable values for the index membership fields
     */
    public FileRecord {
        indexMembershipSolr =
                indexMembershipSolr == null ? List.of() : List.copyOf(indexMembershipSolr);

        indexMembershipActual =
                indexMembershipActual == null ? List.of() : List.copyOf(indexMembershipActual);
    }

    /**
     * Returns a fully qualified "Virtual Path" for
     */
    public String getFullVirtualPath() {
        return isSmb ? "\\\\" + smbHost + "\\" + smbShareName + "\\" + path : path;
    }

    /**
     * Returns a unique identifier that combines SMB host, share name, and path
     * for proper identification of files from different SMB sources.
     */
    public String getUniqueIdentifier() {
        String baseId;

        if (isSmb) {
            // Use an empty string if host is null to avoid "null" literal in the ID
            String host = (smbHost != null) ? smbHost : "local_share";
            String share = (smbShareName != null) ? smbShareName : "unknown_share";
            baseId = host + "||" + share + "||" + path;
        } else {
            baseId = "local||" + path;
        }

        // 1. Normalize
        // Always lowercase for Windows-compatibility
        // and trim to avoid trailing space issues
        String normalized = baseId.toLowerCase().trim();

        // 2. Hash it (MD5 is perfect for unique IDs; it's fast and 32 chars)
        return hashStringWithMD5(normalized);


    }

    /**
     * Hashes a string with MD5 (fast and 32 char output)
     * @param input
     * @return
     */
    private String hashStringWithMD5(String input) {
        try {
            byte[] hash = MessageDigest.getInstance("MD5")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new RuntimeException("Could not generate file hash ID", e);
        }
    }

    /**
     * Creates a new FileRecord for a discovered file.
     * 
     * @param path the file path
     * @param mtime the file modification time
     * @param size the file size
     * @param isSmb whether the file is from an SMB source
     * @param smbShareName the SMB share name (null for non-SMB files)
     * @param smbHost the SMB server host name (null for non-SMB files)
     * @param accessTokens the access tokens for this file
     * @param accessTokensHash the hash of access tokens
     *
     * The indexMembershipActual field determines which indexes a file SHOULD be in,
     * based on its path matching configured indexed directories in the YAML configuration.
     * This field is used during discovery to determine the correct index membership.
     * 
     * The indexMembershipSolr field tracks which indexes the file HAS BEEN indexed into.
     * This field is updated during indexing operations.
     */
    public static FileRecord discovered(Path path, Instant mtime, long size, boolean isSmb, String smbShareName, String smbHost, Set<String> accessTokens, Set<String> denyTokens, String accessTokensHash) {
        long now = Instant.now().toEpochMilli();
        return new FileRecord(
                null, // id will be generated by the DAO
                path.toString(),
                mtime.toEpochMilli(),
                size,
                null,
                IndexingState.DISCOVERED, // state (CORRECTED - must not be null)
                now, // lastSeen (corrected)
                isSmb, // isSmb (corrected)
                smbShareName,
                smbHost,
                List.of(),
                List.of(),
                null,                  // lastAccessTokensCheck → NOT checked yet
                accessTokens,         // accessTokens
                denyTokens,           // denyTokens
                accessTokensHash      // accessTokensHash
        );
    }

    /**
     * Creates a new FileRecord instance with the DISCOVERED state and pre-computed index membership.
     * 
     * This method is used during file discovery when we already know which indexes a file belongs to
     * based on its path and the configured index directories.
     *
     * @param path the file path
     * @param mtime the last modification time of the file
     * @param size the size of the file in bytes
     * @param indexMembershipActual the indexes that this file belongs to based on path configuration
     * @param isSmb whether the file is from an SMB source
     * @param smbShareName the SMB share name (null for non-SMB files)
     * @param smbHost the SMB server host name (null for non-SMB files)
     * @param accessTokens the access tokens for this file
     * @param accessTokensHash the hash of access tokens
     * @return a new FileRecord instance with DISCOVERED state and pre-computed index membership
     */
    public static FileRecord discoveredWithIndexMembership(
            Path path,
            Instant mtime,
            long size,
            List<String> indexMembershipActual,
            boolean isSmb,
            String smbShareName,
            String smbHost,
            Set<String> accessTokens,
            Set<String> denyTokens,
            String accessTokensHash
    ) {
        return new FileRecord(
                null, // id will be generated by the DAO
                path.toString(),
                mtime.toEpochMilli(),
                size,
                null,
                IndexingState.DISCOVERED, // state (CORRECTED - must not be null)
                System.currentTimeMillis(), // lastSeen (corrected)
                isSmb, // isSmb (corrected)
                smbShareName,
                smbHost,
                List.of(),                 // indexMembershipSolr empty
                indexMembershipActual,
                null,                  // lastAccessTokensCheck → NOT checked yet
                accessTokens,            // accessTokens
                denyTokens,              // denyTokens
                accessTokensHash           // accessTokensHash
        );
    }


}