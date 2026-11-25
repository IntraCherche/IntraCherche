package eu.intracherche.intracherche.indexing.model;

import org.apache.tika.metadata.Metadata;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Immutable abstraction representing document content that is ready to be indexed into Solr.
 *
 * <p>An {@code IndexTask} encapsulates all the data required for a single indexing operation,
 * including the extracted text, associated metadata, and optional page-level information.
 * It serves as the contract between the parsing/enrichment stages and the Solr indexing stage
 * within the indexing pipeline.</p>
 *
 * <p>This record is intentionally immutable to ensure thread-safety and to prevent
 * accidental mutation of indexing data as it flows through the pipeline.</p>
 *
 * <p>The task contains all information needed by an {@code IndexWorker} to convert the
 * document content into {@code SolrInputDocument} instances and submit them to Solr
 * without requiring access to the original source files or upstream services.</p>
 *
 * @param documentId      Unique identifier for the document, matching the FileRegistry record ID.
 * @param coreName        The Solr core name to index this document into.
 * @param path            Virtual or physical path of the source file.
 * @param sections        List of content sections (e.g., pages, sheets, slides) with labels and content.
 * @param metadata        Tika Metadata object containing document metadata (author, title, mime_type, etc.).
 * @param accessTokens    Set of access token strings for document-level security.
 * @param denyTokens      SIDs with an explicit Deny ACE (Windows/SMB only; always empty for POSIX).
 * @param mtime           File modification time in milliseconds since epoch.
 * @param smbServer       Optional SMB server host name (null for local files).
 * @param smbShare        Optional SMB share name (null for local files).
 * @param isOcrResult     True when this task was produced by an OcrWorker; used to deprioritise
 *                        scanned-page tasks behind regular document tasks in the IndexTaskQueue.
 */
public record IndexTask(
        String documentId,
        String coreName,
        String path,
        List<SectionContent> sections,
        Metadata metadata,
        Set<String> accessTokens,
        Set<String> denyTokens,
        Long mtime,
        String smbServer,
        String smbShare,
        /** One float[] per section (same order as sections). Null list or null entries mean no vector for that section. */
        List<float[]> sectionVectors,
        boolean isOcrResult
) implements Comparable<IndexTask> {

    /** Non-OCR tasks sort before OCR tasks so they are indexed first. */
    @Override
    public int compareTo(IndexTask other) {
        return Boolean.compare(this.isOcrResult, other.isOcrResult);
    }
    /**
     * Represents a single content section (page, sheet, slide) within a document.
     *
     * @param label      The label or title of the section (e.g., "Page 1", "Sheet1", "Slide 3").
     * @param content    The extracted text content of the section.
     * @param isScanned  Whether this section was extracted via OCR from a scanned page.
     * @param pageNumber The actual PDF page number (1-based). 0 means derive from section list position.
     * @param estimatedPage Whether {@code pageNumber} is an estimate that several sections may share.
     */
    public record SectionContent(
            String label,
            String content,
            boolean isScanned,
            int pageNumber,
            boolean estimatedPage
    ) {
        /** Convenience constructor for non-OCR sections (page number derived from list position). */
        public SectionContent(String label, String content, boolean isScanned) {
            this(label, content, isScanned, 0);
        }

        /** Convenience constructor for sections on a known page. */
        public SectionContent(String label, String content, boolean isScanned, int pageNumber) {
            this(label, content, isScanned, pageNumber, false);
        }

        /**
         * Creates a SectionContent from an IndexedSection for queue transfer.
         */
        public static SectionContent fromIndexedSection(
                eu.intracherche.intracherche.indexing.parser.IndexedSection section
        ) {
            return new SectionContent(section.label(), section.content(), section.isScanned(), section.pageNumber(),
                    section.estimatedPage());
        }
    }

    /**
     * Convenience factory for creating an {@code IndexTask} with minimal fields.
     *
     * @param documentId    Unique identifier for the document.
     * @param coreName      The Solr core name to index into.
     * @param path          Virtual or physical path of the source file.
     * @param extractedText The full text extracted from the document.
     * @param metadata      Key-value pairs of metadata associated with the document.
     * @return an {@code IndexTask} with a single default section and null for optional fields.
     */
    public static IndexTask of(String documentId, String coreName, String path,
                               String extractedText, Map<String, Object> metadata) {
        org.apache.tika.metadata.Metadata tikaMeta = new org.apache.tika.metadata.Metadata();
        if (metadata != null) {
            for (Map.Entry<String, Object> entry : metadata.entrySet()) {
                if (entry.getValue() != null) {
                    tikaMeta.set(entry.getKey(), entry.getValue().toString());
                }
            }
        }
        return new IndexTask(
                documentId, coreName, path,
                List.of(new SectionContent("Document", extractedText, false)),
                tikaMeta, null, null, null, null, null, null, false
        );
    }

    /**
     * Factory for creating an {@code IndexTask} from pipeline output.
     * This is the primary factory used by FileIndexingService after parsing and enrichment.
     *
     * @param documentId      Unique identifier for the document.
     * @param coreName        The Solr core name to index into.
     * @param path            Virtual or physical path of the source file.
     * @param sections        List of indexed sections from the parser.
     * @param metadata        Tika metadata from parsing/enrichment.
     * @param accessTokens    Access tokens for document security.
     * @param denyTokens      SIDs with an explicit Deny ACE (Windows/SMB only; always empty for POSIX).
     * @param mtime           File modification time.
     * @param smbServer       SMB server host (nullable).
     * @param smbShare        SMB share name (nullable).
     * @return a fully populated {@code IndexTask} ready for async processing.
     */
    public static IndexTask fromPipeline(
            String documentId,
            String coreName,
            String path,
            List<eu.intracherche.intracherche.indexing.parser.IndexedSection> sections,
            Metadata metadata,
            Set<String> accessTokens,
            Set<String> denyTokens,
            Long mtime,
            String smbServer,
            String smbShare,
            List<float[]> sectionVectors
    ) {
        List<SectionContent> sectionContents = sections.stream()
                .map(SectionContent::fromIndexedSection)
                .toList();

        return new IndexTask(
                documentId,
                coreName,
                path,
                sectionContents,
                metadata,
                accessTokens,
                denyTokens,
                mtime,
                smbServer,
                smbShare,
                sectionVectors,
                false
        );
    }
}
