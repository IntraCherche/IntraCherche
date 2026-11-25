package eu.intracherche.intracherche.indexing.parser;

import org.apache.tika.metadata.Metadata;

import java.util.Collections;
import java.util.List;

/**
 *  Result class for parsing operations
 * ParseResult typically contains:
 *
 * Extracted text
 *
 * Metadata (author, title, dates, MIME type)
 *
 * Embedded documents (optional)
 *
 * Parsing warnings
 */
public record ParseResult(
        List<IndexedSection> sections,
        Metadata metadata,
        String detectedMimeType,
        int ocrTasksEnqueued,
        int textPageCount,
        boolean textLimited
) {
    public ParseResult(List<IndexedSection> sections, Metadata metadata, String detectedMimeType,
                       int ocrTasksEnqueued, int textPageCount) {
        this(sections, metadata, detectedMimeType, ocrTasksEnqueued, textPageCount, false);
    }

    public ParseResult(List<IndexedSection> sections, Metadata metadata, String detectedMimeType) {
        this(sections, metadata, detectedMimeType, 0, 0);
    }

    public ParseResult(List<IndexedSection> sections, Metadata metadata, String detectedMimeType, int ocrTasksEnqueued) {
        this(sections, metadata, detectedMimeType, ocrTasksEnqueued, 0);
    }

    public List<IndexedSection> getSections() {
        return sections != null ? sections : Collections.emptyList();
    }

    public boolean hasPendingOcr() {
        return ocrTasksEnqueued > 0;
    }
}
