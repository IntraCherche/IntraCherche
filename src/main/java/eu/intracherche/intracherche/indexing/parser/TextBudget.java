package eu.intracherche.intracherche.indexing.parser;

import java.util.ArrayList;
import java.util.List;

/**
 * How much text one file may produce (indexing-config.maxTextChars), spent as it is extracted.
 *
 * <p>A small file can expand into an enormous text — a crafted document, a spreadsheet of a million
 * rows — and every character is then held in memory, sent to the embedding and NER services and
 * stored in Solr. The extractors that build text as they go stop once the budget is spent; the
 * sections of every format are then cut to it in {@link #cap}. The file stays searchable on the
 * text kept, and {@link #truncated()} records that the rest was left out.
 */
public final class TextBudget {

    private final long maxChars;
    private long used;
    private boolean truncated;

    public TextBudget(long maxChars) {
        this.maxChars = maxChars;
    }

    public static TextBudget unlimited() {
        return new TextBudget(Long.MAX_VALUE);
    }

    public long maxChars() {
        return maxChars;
    }

    /** The part of {@code text} that fits; empty once the budget is spent. */
    public String take(String text) {
        if (text == null || text.isEmpty()) return text;
        long left = maxChars - used;
        if (left <= 0) {
            truncated = true;
            return "";
        }
        if (text.length() <= left) {
            used += text.length();
            return text;
        }
        truncated = true;
        used = maxChars;
        return text.substring(0, (int) left);
    }

    public boolean exhausted() {
        return used >= maxChars;
    }

    /** For a Tika handler's write limit: what is left, as an int. */
    public int handlerLimit() {
        return (int) Math.min(Integer.MAX_VALUE - 1, Math.max(0, maxChars - used));
    }

    public void markTruncated() {
        truncated = true;
    }

    public boolean truncated() {
        return truncated;
    }

    /**
     * Cuts {@code sections} to {@code maxChars} in total, whatever produced them. Returns the list
     * unchanged when it fits; sets {@link #truncated()} when it does not.
     */
    public List<IndexedSection> cap(List<IndexedSection> sections) {
        long total = 0;
        for (IndexedSection s : sections) total += s.content() != null ? s.content().length() : 0;
        if (total <= maxChars) return sections;
        truncated = true;
        List<IndexedSection> kept = new ArrayList<>();
        long left = maxChars;
        for (IndexedSection s : sections) {
            String content = s.content() != null ? s.content() : "";
            if (left <= 0) break;
            if (content.length() <= left) {
                kept.add(s);
                left -= content.length();
            } else {
                kept.add(new IndexedSection(s.label(), content.substring(0, (int) left), s.isScanned(), s.pageNumber(),
                        s.estimatedPage()));
                left = 0;
            }
        }
        return kept;
    }
}
