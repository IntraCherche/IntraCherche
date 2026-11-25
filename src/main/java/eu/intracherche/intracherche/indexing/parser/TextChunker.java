package eu.intracherche.intracherche.indexing.parser;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits plain text into semantically coherent chunks at paragraph boundaries.
 *
 * Target size (~1500 chars, ~300 French words) fits within the 512-token limit
 * of multilingual embedding models (e.g. multilingual-e5-base). The last
 * paragraph of each chunk is prepended to the next one as overlap so that
 * context is not lost at boundaries.
 */
public class TextChunker {

    static final int TARGET_CHARS = 1500;
    static final int HARD_MAX_CHARS = 3000;

    private TextChunker() {}

    /**
     * Splits {@code text} into chunks at double-newline paragraph boundaries.
     * Returns a single-element list when the text is already short enough.
     */
    public static List<String> chunkByParagraphs(String text) {
        if (text == null || text.isBlank()) return List.of();
        if (text.length() <= TARGET_CHARS) return List.of(text.trim());

        String[] paragraphs = text.split("\n{2,}");
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        String overlap = "";

        for (String raw : paragraphs) {
            String para = raw.trim();
            if (para.isEmpty()) continue;

            if (current.length() > 0 && current.length() + para.length() > TARGET_CHARS) {
                chunks.add(current.toString().trim());
                current = new StringBuilder();
                if (!overlap.isEmpty()) {
                    current.append(overlap).append("\n\n");
                }
                overlap = "";
            }

            if (para.length() > HARD_MAX_CHARS) {
                for (String sentence : splitOnSentences(para)) {
                    current.append(sentence).append(" ");
                    if (current.length() >= TARGET_CHARS) {
                        chunks.add(current.toString().trim());
                        current = new StringBuilder();
                        overlap = "";
                    }
                }
            } else {
                current.append(para).append("\n\n");
                overlap = para;
            }
        }

        String tail = current.toString().trim();
        if (!tail.isBlank()) {
            chunks.add(tail);
        }

        return chunks.isEmpty() ? List.of(text.trim()) : chunks;
    }

    /**
     * Splits on sentence-ending punctuation followed by a space and an uppercase letter.
     * Handles French abbreviations imperfectly but good enough for chunk overflow.
     */
    private static List<String> splitOnSentences(String text) {
        return List.of(text.split("(?<=[.!?])\\s+(?=\\p{Lu})"));
    }
}
