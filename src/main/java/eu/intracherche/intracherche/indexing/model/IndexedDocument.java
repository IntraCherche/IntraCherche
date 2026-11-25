package eu.intracherche.intracherche.indexing.model;

import org.apache.tika.metadata.Metadata;

import java.nio.file.Path;
import java.util.Set;

/**
 * Stage 3 – Normalization
 * Convert parser output into your internal indexing model,
 * not Solr's model yet.
 */
public record IndexedDocument(
        String id,
        Path path,
        String content,
        Metadata metadata,
        Set<String> accessTokens,
        /** SIDs with an explicit Deny ACE (Windows/SMB only; always empty for POSIX). A user's
         *  SID being in this set overrides that same SID matching {@link #accessTokens}. */
        Set<String> denyTokens,
        String indexSource,
        Long mtime,
        String smbServer,
        String smbShare
) {
    public IndexedDocument withAccessTokens(Set<String> aclTokens) {
        return new IndexedDocument(
                id, path, content, metadata, aclTokens, denyTokens, indexSource, mtime, smbServer, smbShare
        );
    }

    public IndexedDocument withDenyTokens(Set<String> denyTokens) {
        return new IndexedDocument(
                id, path, content, metadata, accessTokens, denyTokens, indexSource, mtime, smbServer, smbShare
        );
    }

    public IndexedDocument withIndexSource(String indexSource) {
        return new IndexedDocument(
                id, path, content, metadata, accessTokens, denyTokens, indexSource, mtime, smbServer, smbShare
        );
    }

    public IndexedDocument withMtime(Long mtime) {
        return new IndexedDocument(
                id, path, content, metadata, accessTokens, denyTokens, indexSource, mtime, smbServer, smbShare
        );
    }

    public IndexedDocument withSmbInfo(String smbServer, String smbShare) {
        return new IndexedDocument(
                id, path, content, metadata, accessTokens, denyTokens, indexSource, mtime, smbServer, smbShare
        );
    }
}
