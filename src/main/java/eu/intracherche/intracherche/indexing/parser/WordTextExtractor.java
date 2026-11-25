package eu.intracherche.intracherche.indexing.parser;

import org.apache.tika.exception.WriteLimitReachedException;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.sax.BodyContentHandler;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.ocr.TesseractOCRConfig;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

public class WordTextExtractor {

    // Tika marks no page breaks in a .docx, so pages are estimated: a page holds 52 lines of at
    // most 62 characters, a tab counting as 4. A hard page break (\f), when Tika gives one,
    // always starts a new page.
    static final int LINE_CHARS = 62;
    static final int PAGE_LINES = 52;
    /** How much of a section's first line is looked for in the text to place it. */
    private static final int HEAD_CHARS = 60;

    public record ExtractionResult(List<IndexedSection> sections, int pageCount) {}

    public static ExtractionResult extractPages(byte[] data) throws Exception {
        return extractPages(data, TextBudget.unlimited());
    }

    public static ExtractionResult extractPages(byte[] data, TextBudget budget) throws Exception {
        String fullText = extractFullTextOnly(data, budget);
        if (fullText == null || fullText.isBlank()) return new ExtractionResult(List.of(), 0);
        return sections(fullText.replace("\t", "    ")
                                .replace("\r\n", "\n")
                                .replace("\r", "\n"));
    }

    /**
     * Cuts the text into search-sized sections (see {@link TextChunker}), each numbered with the
     * page it starts on.
     */
    static ExtractionResult sections(String fullText) {
        List<IndexedSection> sections = new ArrayList<>();
        int pagesBefore = 0;

        for (String part : fullText.split("\\f")) {
            List<Integer> pageStarts = pageStarts(part);
            int cursor = 0;
            for (String chunk : TextChunker.chunkByParagraphs(part)) {
                // A chunk is trimmed paragraphs or sentences of the part, so its first line is
                // found as written, at or after the previous chunk's start (chunks overlap).
                String head = chunk.lines().findFirst().orElse("");
                int at = part.indexOf(head.substring(0, Math.min(head.length(), HEAD_CHARS)), cursor);
                if (at >= 0) cursor = at;
                int page = pagesBefore + pageAt(pageStarts, cursor);
                sections.add(IndexedSection.onEstimatedPage("Page " + page, chunk, page));
            }
            pagesBefore += pageStarts.size();
        }

        return new ExtractionResult(sections, pagesBefore);
    }

    /** Offsets in {@code part} where each estimated page begins; the first is 0. */
    static List<Integer> pageStarts(String part) {
        List<Integer> starts = new ArrayList<>(List.of(0));
        int lines = 0;
        int offset = 0;
        for (String line : part.split("\n", -1)) {
            // A long line wraps onto several; an empty one still takes one
            int wrapped = Math.max(1, (line.length() + LINE_CHARS - 1) / LINE_CHARS);
            if (lines > 0 && lines + wrapped > PAGE_LINES) {
                starts.add(offset);
                lines = 0;
            }
            lines += wrapped;
            offset += line.length() + 1;
        }
        return starts;
    }

    /** The 1-based page holding {@code offset}. */
    private static int pageAt(List<Integer> pageStarts, int offset) {
        int page = 0;
        while (page < pageStarts.size() && pageStarts.get(page) <= offset) page++;
        return page;
    }

    private static String extractFullTextOnly(byte[] data, TextBudget budget) throws Exception {
        AutoDetectParser parser = new AutoDetectParser();
        BodyContentHandler handler = new BodyContentHandler(budget.handlerLimit());
        Metadata meta = new Metadata();
        ParseContext ctx = new ParseContext();
        TesseractOCRConfig cfg = new TesseractOCRConfig();
        cfg.setSkipOcr(true);
        ctx.set(TesseractOCRConfig.class, cfg);
        try (InputStream is = new ByteArrayInputStream(data)) {
            parser.parse(is, handler, meta, ctx);
        } catch (Exception e) {
            // The text budget is spent: keep what was extracted up to it.
            if (!WriteLimitReachedException.isWriteLimitReached(e)) throw e;
            budget.markTruncated();
        }
        return budget.take(handler.toString());
    }
}
