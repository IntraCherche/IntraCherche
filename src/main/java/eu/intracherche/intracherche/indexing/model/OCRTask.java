package eu.intracherche.intracherche.indexing.model;

import org.apache.tika.metadata.Metadata;

import java.util.Set;

/**
 * Immutable abstraction representing a single OCR request for a scanned PDF page.
 *
 * <p>The PNG image for the page is rendered by the producer ({@code PdfPageExtractor})
 * while the PDF is already open, so workers receive ready-to-OCR bytes and never
 * need to re-load or re-parse the PDF.</p>
 *
 * @param documentId   Unique identifier for the document (matches FileRegistry record ID).
 * @param coreName     Solr core name to index the OCR result into.
 * @param filePath     Virtual or physical path of the source file (for display / IndexTask).
 * @param pngBytes     PNG-encoded image of the page, pre-rendered by the producer.
 * @param pageNumber   1-based page number (used for the "Page N" section label).
 * @param metadata     Tika metadata already available for the document (may be null).
 * @param accessTokens Access token strings for document-level security.
 * @param denyTokens   SIDs with an explicit Deny ACE (Windows/SMB only; always empty for POSIX).
 * @param mtime        File modification time in milliseconds since epoch.
 * @param smbServer    SMB server host name (null for local files).
 * @param smbShare     SMB share name (null for local files).
 */
public record OCRTask(
        String documentId,
        String coreName,
        String filePath,
        byte[] pngBytes,
        int pageNumber,
        Metadata metadata,
        Set<String> accessTokens,
        Set<String> denyTokens,
        Long mtime,
        String smbServer,
        String smbShare
) {
    public static OCRTask of(String documentId, String coreName, String filePath, byte[] pngBytes,
                             int pageNumber, Metadata metadata,
                             Set<String> accessTokens, Set<String> denyTokens, Long mtime,
                             String smbServer, String smbShare) {
        return new OCRTask(documentId, coreName, filePath, pngBytes, pageNumber,
                metadata, accessTokens, denyTokens, mtime, smbServer, smbShare);
    }
}
