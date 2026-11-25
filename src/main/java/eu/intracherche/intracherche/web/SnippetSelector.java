package eu.intracherche.intracherche.web;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Picks which pages of a matched document show a snippet in the result list.
 *
 * <p>Taking the best-ranked pages is not enough when the terms are spread over the document: the
 * pages that repeat the context words ("saint michel de maurienne") outrank the only page holding
 * the subject ("automates"), and the user never sees why the document matched. So the best page
 * comes first, then each next slot goes to the page whose snippet shows the most search terms not
 * yet shown, rank breaking ties and filling the remaining slots.</p>
 *
 * <p>Which terms a snippet shows is read from its highlight tags, which is also what the user
 * sees. A highlighted word covers a term when it starts with it, as the EdgeNGram content field
 * matches prefixes; both sides are lowercased and accent-folded like the index.</p>
 */
final class SnippetSelector {

    private static final Pattern HIGHLIGHT = Pattern.compile("<b>(.*?)</b>", Pattern.DOTALL);
    private static final Set<String> OPERATORS = Set.of("and", "or", "not");

    private SnippetSelector() {}

    /** Lowercased, accent-folded words of the query, operators left out. */
    static List<String> queryTerms(String q) {
        if (q == null) return List.of();
        return Arrays.stream(fold(q).split("[^\\p{L}\\p{N}]+"))
                .filter(t -> !t.isEmpty() && !OPERATORS.contains(t))
                .distinct()
                .toList();
    }

    /**
     * Indices of the snippets to show, in display order.
     *
     * @param snippets highlighted snippets in rank order (null or blank entries are never picked)
     */
    static List<Integer> select(List<String> snippets, List<String> queryTerms, int max) {
        List<Set<String>> covers = new ArrayList<>();
        for (String snippet : snippets) covers.add(termsShown(snippet, queryTerms));

        List<Integer> chosen = new ArrayList<>();
        Set<String> shown = new HashSet<>();
        while (chosen.size() < max) {
            int best = -1;
            int bestGain = -1;
            for (int i = 0; i < snippets.size(); i++) {
                if (chosen.contains(i) || snippets.get(i) == null || snippets.get(i).isBlank()) continue;
                Set<String> gain = new HashSet<>(covers.get(i));
                gain.removeAll(shown);
                // The first slot always goes to the best-ranked page: it is why the document ranks where it does.
                if (chosen.isEmpty()) { best = i; break; }
                if (gain.size() > bestGain) { best = i; bestGain = gain.size(); }
            }
            if (best < 0) break;
            chosen.add(best);
            shown.addAll(covers.get(best));
        }
        return chosen;
    }

    private static Set<String> termsShown(String snippet, List<String> queryTerms) {
        Set<String> shown = new HashSet<>();
        if (snippet == null) return shown;
        Matcher m = HIGHLIGHT.matcher(snippet);
        while (m.find()) {
            for (String word : fold(m.group(1)).split("[^\\p{L}\\p{N}]+")) {
                for (String term : queryTerms) {
                    if (!word.isEmpty() && word.startsWith(term)) shown.add(term);
                }
            }
        }
        return shown;
    }

    private static String fold(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase();
    }
}
