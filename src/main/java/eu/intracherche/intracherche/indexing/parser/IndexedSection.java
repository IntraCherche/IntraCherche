package eu.intracherche.intracherche.indexing.parser;

/**
 * Record representing a section of content with a label, typically used for
 * sheets in spreadsheets or slides in presentations.
 *
 * @param label         The label or title of the section (e.g., "Page 1", "Sheet1", "Slide 3")
 * @param content       The content of the section
 * @param isScanned     Whether this section was extracted via OCR from a scanned (image-based) page
 * @param pageNumber    The physical page number as shown in a PDF reader's page browser (1-based).
 *                      0 means "derive from the section's position in the list" (used by extractors
 *                      that never skip entries, e.g. spreadsheets/slides).
 * @param estimatedPage Whether {@code pageNumber} is an estimate that several sections may share
 *                      (Word and OpenDocument text, whose pages the text does not mark).
 */
public record IndexedSection(String label, String content, boolean isScanned, int pageNumber,
                             boolean estimatedPage) {

    /**
     * Convenience constructor for non-scanned sections (the common case).
     */
    public IndexedSection(String label, String content) {
        this(label, content, false, 0);
    }

    /**
     * Convenience constructor for sections without an explicit page number
     * (page number derived from list position).
     */
    public IndexedSection(String label, String content, boolean isScanned) {
        this(label, content, isScanned, 0);
    }

    /**
     * Convenience constructor for sections on a known page.
     */
    public IndexedSection(String label, String content, boolean isScanned, int pageNumber) {
        this(label, content, isScanned, pageNumber, false);
    }

    /** A section of text on the page it is estimated to start on. */
    public static IndexedSection onEstimatedPage(String label, String content, int pageNumber) {
        return new IndexedSection(label, content, false, pageNumber, true);
    }
}
