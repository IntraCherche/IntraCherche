package eu.intracherche.intracherche.sherlock;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks, in code, that every quotation in an answer is in the passages the model read.
 *
 * <p>The model can be wrong about what a passage means; it must never be allowed to put words
 * in a document's mouth unnoticed. A quotation found in no passage — or in another passage than
 * the one it is cited from, which sends the user to the wrong page — is flagged, whatever the
 * model says about it.
 *
 * <p>Matching compares letters and digits only, ignoring case and accents: OCR glues and splits
 * words ("recemmentfaitl'objet", "procé- dures") and the model re-spaces them as it copies, so
 * spacing, punctuation and hyphens never count; a changed, added or missing letter always does.
 * A glyph the PDF's font maps to a private-use character — U+E03C for "tt" in the Wikipedia
 * PDFs, so "attentats" is stored as "a\uE03Centats" — stands for one to three letters. An
 * ellipsis or a bracketed insertion inside a quotation ("début … fin", "[et]") marks a cut:
 * each piece must be there, in order.
 */
final class QuoteVerifier {

    /** « … », “ … ” or " … " on one line. */
    private static final Pattern QUOTE = Pattern.compile("«[^«»]+»|“[^“”]+”|\"[^\"\\n]+\"");
    private static final Pattern CITATION = Pattern.compile("\\[DOCUMENT (\\d+) - [^\\]]+\\]");
    /** "…", "...", "(…)" and any bracketed text: "[…]", "[et]", or a citation left inside the quotes. */
    private static final Pattern CUT = Pattern.compile("…|\\.\\.\\.|\\[[^\\]]*\\]|\\(\\s*(?:…|\\.\\.\\.)\\s*\\)");
    /** Stands, in a folded passage, for a glyph extraction could not name. */
    private static final char UNKNOWN_GLYPH = '#';
    private static final int MAX_LETTERS_PER_GLYPH = 3;

    private QuoteVerifier() {}

    static List<SherlockQuote> verify(String answer, List<SherlockResult> passages) {
        List<SherlockQuote> quotes = new ArrayList<>();
        if (answer == null || passages == null || passages.isEmpty()) return quotes;
        List<String> folded = passages.stream().map(p -> fold(p.context())).toList();

        Matcher m = QUOTE.matcher(answer);
        while (m.find()) {
            String inside = answer.substring(m.start() + 1, m.end() - 1);
            List<String> pieces = CUT.splitAsStream(inside)
                    .map(QuoteVerifier::fold)
                    .filter(p -> !p.isEmpty())
                    .toList();
            if (pieces.isEmpty()) continue;
            Integer cited = citedAfter(answer, m.end());
            if (cited == null) {
                Matcher within = CITATION.matcher(inside);
                if (within.find()) cited = Integer.valueOf(within.group(1));
            }
            Integer foundIn = null;
            // The cited passage first, so a quotation that is in two passages is found where it is cited.
            if (cited != null && cited >= 1 && cited <= folded.size() && containsInOrder(folded.get(cited - 1), pieces)) {
                foundIn = cited;
            } else {
                for (int i = 0; i < folded.size() && foundIn == null; i++) {
                    if (containsInOrder(folded.get(i), pieces)) foundIn = i + 1;
                }
            }
            quotes.add(new SherlockQuote(m.start(), m.end(), cited, foundIn));
        }
        return quotes;
    }

    /**
     * The passage cited for a quotation: the first citation after it, as long as neither another
     * quotation nor a paragraph break comes first. "« … », selon le rapport [DOCUMENT 2 - …]" cites
     * document 2; a citation two paragraphs down belongs to something else.
     */
    private static Integer citedAfter(String answer, int from) {
        Matcher c = CITATION.matcher(answer);
        if (!c.find(from)) return null;
        String between = answer.substring(from, c.start());
        if (between.contains("\n\n") || QUOTE.matcher(between).find()) return null;
        return Integer.valueOf(c.group(1));
    }

    private static boolean containsInOrder(String haystack, List<String> pieces) {
        int at = 0;
        for (String piece : pieces) {
            at = indexAfter(haystack, piece, at);
            if (at < 0) return false;
        }
        return true;
    }

    /** Where the first occurrence of {@code needle} at or after {@code from} ends, or -1. */
    private static int indexAfter(String haystack, String needle, int from) {
        for (int i = from; i < haystack.length(); i++) {
            int end = matchAt(haystack, i, needle, 0);
            if (end >= 0) return end;
        }
        return -1;
    }

    private static int matchAt(String h, int i, String n, int j) {
        while (j < n.length()) {
            if (i >= h.length()) return -1;
            char c = h.charAt(i);
            if (c == UNKNOWN_GLYPH) {
                for (int k = 1; k <= MAX_LETTERS_PER_GLYPH; k++) {
                    int end = j + k >= n.length() ? i + 1 : matchAt(h, i + 1, n, j + k);
                    if (end >= 0) return end;
                }
                return -1;
            }
            if (c != n.charAt(j)) return -1;
            i++;
            j++;
        }
        return i;
    }

    /** Letters and digits only, lower case, no accents; a private-use glyph becomes {@link #UNKNOWN_GLYPH}. */
    static String fold(String text) {
        if (text == null) return "";
        String s = Normalizer.normalize(text, Normalizer.Form.NFKD).replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT);
        s = s.replaceAll("\\p{Co}", String.valueOf(UNKNOWN_GLYPH));
        return s.replaceAll("[^\\p{L}\\p{N}" + UNKNOWN_GLYPH + "]+", "");
    }
}
