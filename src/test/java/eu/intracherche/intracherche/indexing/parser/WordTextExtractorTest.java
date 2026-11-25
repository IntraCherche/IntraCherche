package eu.intracherche.intracherche.indexing.parser;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tika marks no page breaks in a .docx, so its sections are numbered with an estimated page
 * (52 lines of 62 characters) rather than their position: "page 10" of a search result used to
 * be the tenth ~1500-character section, about page 5.
 */
class WordTextExtractorTest {

    /**
     * Paragraph {@code n}, one full line long, so that it and the blank line after it take two
     * lines. It ends a sentence, as a .docx paragraph Tika separates with a single newline needs
     * to be for {@link TextChunker} to cut there.
     */
    private static String paragraph(int n) {
        String text = "Paragraphe " + n + " du compte rendu ";
        return text + "x".repeat(WordTextExtractor.LINE_CHARS - text.length() - 1) + ".";
    }

    /** 150 paragraphs: 300 lines, 26 paragraphs a page, so six pages. */
    private static final String TEXT = IntStream.range(0, 150)
            .mapToObj(WordTextExtractorTest::paragraph)
            .collect(Collectors.joining("\n\n"));

    @Test
    void each_section_is_numbered_with_the_page_it_starts_on() {
        WordTextExtractor.ExtractionResult result = WordTextExtractor.sections(TEXT);

        assertThat(result.pageCount()).isEqualTo(6);
        assertThat(result.sections()).hasSizeGreaterThan(result.pageCount());
        for (IndexedSection section : result.sections()) {
            int firstParagraph = Integer.parseInt(section.content().split(" ")[1]);
            assertThat(section.pageNumber()).as(section.content().lines().findFirst().orElseThrow())
                    .isEqualTo(firstParagraph * 2 / WordTextExtractor.PAGE_LINES + 1);
            assertThat(section.estimatedPage()).isTrue();
            assertThat(section.label()).isEqualTo("Page " + section.pageNumber());
        }
        assertThat(result.sections().getLast().pageNumber()).isEqualTo(6);
    }

    @Test
    void a_line_longer_than_a_printed_line_takes_several() {
        String longLine = "y".repeat(WordTextExtractor.LINE_CHARS * 3);
        String text = String.join("\n", "a".repeat(10), longLine, "b");
        assertThat(WordTextExtractor.pageStarts(text)).containsExactly(0);
        String overAPage = (longLine + "\n").repeat(WordTextExtractor.PAGE_LINES / 3) + "c\nd";
        assertThat(WordTextExtractor.pageStarts(overAPage)).hasSize(2);
    }

    @Test
    void a_hard_page_break_starts_a_new_page() {
        WordTextExtractor.ExtractionResult result = WordTextExtractor.sections("Contenu page A.\fContenu page B.");

        assertThat(result.pageCount()).isEqualTo(2);
        assertThat(result.sections()).extracting(IndexedSection::pageNumber).containsExactly(1, 2);
        assertThat(result.sections().get(1).content()).isEqualTo("Contenu page B.");
    }

    @Test
    void a_docx_gets_estimated_pages() throws Exception {
        WordTextExtractor.ExtractionResult result = WordTextExtractor.extractPages(docx(150));

        assertThat(result.pageCount()).isGreaterThan(1);
        assertThat(result.sections().getFirst().pageNumber()).isEqualTo(1);
        assertThat(result.sections().getLast().pageNumber()).isEqualTo(result.pageCount());
        assertThat(result.sections()).extracting(IndexedSection::pageNumber).isSorted();
    }

    private static byte[] docx(int paragraphs) throws Exception {
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (int i = 0; i < paragraphs; i++) doc.createParagraph().createRun().setText(paragraph(i));
            doc.write(out);
            return out.toByteArray();
        }
    }
}
