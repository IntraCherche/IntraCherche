package eu.intracherche.intracherche.indexing.parser;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TextChunkerTest {

    @Test
    void shortTextReturnsSingleChunk() {
        String text = "Bonjour monde.";
        List<String> chunks = TextChunker.chunkByParagraphs(text);
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0)).isEqualTo("Bonjour monde.");
    }

    @Test
    void nullAndBlankReturnEmpty() {
        assertThat(TextChunker.chunkByParagraphs(null)).isEmpty();
        assertThat(TextChunker.chunkByParagraphs("   ")).isEmpty();
    }

    @Test
    void longTextSplitsAtParagraphBoundaries() {
        String para = "a".repeat(600);
        String text = para + "\n\n" + para + "\n\n" + para;

        List<String> chunks = TextChunker.chunkByParagraphs(text);

        // Three 600-char paragraphs: first two fit in one chunk (1200 < 1500),
        // third overflows into a second chunk
        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0)).contains(para);
    }

    @Test
    void overlapCarriesLastParagraphToNextChunk() {
        // Build text where each paragraph is 800 chars — forces a new chunk after each one
        String p1 = "premier ".repeat(100).trim();   // 800 chars
        String p2 = "deuxième ".repeat(89).trim();   // ~800 chars
        String p3 = "troisième ".repeat(80).trim();  // 800 chars

        String text = p1 + "\n\n" + p2 + "\n\n" + p3;
        List<String> chunks = TextChunker.chunkByParagraphs(text);

        assertThat(chunks).hasSizeGreaterThanOrEqualTo(2);
        // Second chunk should start with the overlap (last paragraph of previous chunk)
        assertThat(chunks.get(1)).startsWith(p1.substring(0, 10));
    }

    @Test
    void noParagraphBreaksUsesEntireTextAsOneChunk() {
        // TEXT without double newlines, shorter than TARGET_CHARS
        String text = "Une ligne. Une autre ligne. Encore une.";
        List<String> chunks = TextChunker.chunkByParagraphs(text);
        assertThat(chunks).hasSize(1);
    }

    @Test
    void allChunksWithinHardMax() {
        // Generate text well above hard max to trigger sentence splitting
        String sentence = "Ceci est une phrase française typique qui fait environ soixante caractères. ";
        String text = sentence.repeat(60); // ~4200 chars, no paragraph breaks

        List<String> chunks = TextChunker.chunkByParagraphs(text);

        for (String chunk : chunks) {
            assertThat(chunk.length()).isLessThanOrEqualTo(TextChunker.HARD_MAX_CHARS);
        }
    }
}
