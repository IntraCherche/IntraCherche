package eu.intracherche.intracherche.web;

import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.util.ClientUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Applies the search terms to whole documents rather than single pages.
 *
 * <p>Documents are indexed page by page, so a query naming both the subject and its context
 * ("automates saint michel de maurienne") often has no page containing every word: the town is
 * on the cover and in the headers, the subject on page 2. Strict AND then finds nothing, and OR
 * relaxation buries the right document among any page mentioning one of the words. Two rules
 * therefore work per document, each through a {@code {!join from=parent_id to=parent_id}}
 * filter. Every page of a document lives in the same core, so the join also holds when the
 * search spans several cores.</p>
 *
 * <ul>
 *   <li>{@link #applyInclusions}: the retry between strict AND and OR — every positive unit must
 *   appear on at least one page of the document.</li>
 *   <li>{@link #applyExclusions}: always — a {@code -unit} (or {@code NOT unit}, which the lucene
 *   parser treats the same) removes the whole document as soon as one of its pages contains it,
 *   not just that page.</li>
 * </ul>
 *
 * <p>A unit is a word, a word with {@code *} or {@code ?} wildcards, or a quoted phrase (which
 * must still sit on one page). Queries using any other syntax — parentheses, {@code field:value},
 * AND/OR, {@code +}, fuzzy, boosts, ranges, regex — are not parsed and keep page-level
 * behaviour: splitting them into independent units would change what they mean.</p>
 *
 * <p>The units are only filters: q keeps them with q.op=OR so pages are still ranked by what
 * they contain. Each join parses its unit with its own {@code q.op=AND}, so a hyphenated word
 * ("saint-michel") still needs all its parts, and the request-level q.op does not reach it.</p>
 */
final class DocumentLevelMatch {

    /** One join per unit; past this many, a query keeps page-level behaviour. */
    static final int MAX_UNITS = 8;

    private static final String INCLUDE_PREFIX = "docterm";
    private static final String EXCLUDE_PREFIX = "docnot";
    private static final Set<String> OPERATORS = Set.of("AND", "OR", "&&", "||");
    // Syntax a unit cannot contain outside a phrase. '*' and '?' are allowed (wildcards), '-' only
    // inside a word or once in front of it.
    private static final Pattern WORD_SYNTAX = Pattern.compile("[():\\[\\]{}~^\\\\/+!\"]");
    private static final Pattern UNIT = Pattern.compile("(-?)(?:\"([^\"]*)\"|([^\\s\"]+))");

    /** Positive and negated units of a query, already escaped for the lucene parser. */
    record Units(List<String> included, List<String> excluded) {}

    private DocumentLevelMatch() {}

    /**
     * Splits a query into units, or returns null when it uses syntax this class does not parse,
     * has no positive unit, or has more than {@link #MAX_UNITS} units.
     */
    static Units parse(String q) {
        if (q == null || q.isBlank()) return null;
        List<String> included = new ArrayList<>();
        List<String> excluded = new ArrayList<>();
        Matcher m = UNIT.matcher(q);
        int end = 0;
        boolean notPending = false;
        while (m.find()) {
            if (!q.substring(end, m.start()).isBlank()) return null; // e.g. an unbalanced quote
            end = m.end();
            if ("NOT".equals(m.group(3))) {
                if (notPending) return null;
                notPending = true;
                continue;
            }
            boolean negated = notPending || !m.group(1).isEmpty();
            if (notPending && !m.group(1).isEmpty()) return null; // NOT -word
            notPending = false;
            String unit;
            if (m.group(2) != null) {
                if (m.group(2).isBlank()) return null;
                unit = "\"" + m.group(2).replace("\\", "\\\\") + "\"";
            } else {
                String word = m.group(3);
                if (OPERATORS.contains(word) || word.startsWith("-") || WORD_SYNTAX.matcher(word).find()) return null;
                unit = escapeKeepingWildcards(word);
            }
            (negated ? excluded : included).add(unit);
        }
        if (notPending || !q.substring(end).isBlank()) return null;
        if (included.isEmpty() || included.size() + excluded.size() > MAX_UNITS) return null;
        return new Units(List.copyOf(included), List.copyOf(excluded));
    }

    /**
     * Whether the document-level retry can find more than page-level AND did: with a single
     * positive unit both are the same query.
     */
    static boolean canWidenToDocument(Units units) {
        return units != null && units.included().size() >= 2;
    }

    /** Requires every positive unit somewhere in the document; {@link #removeInclusions} undoes it. */
    static void applyInclusions(SolrQuery query, Units units, String field) {
        for (int i = 0; i < units.included().size(); i++) {
            query.set(INCLUDE_PREFIX + i, subQuery(field, units.included().get(i)));
            query.addFilterQuery(join(INCLUDE_PREFIX, i));
        }
    }

    static void removeInclusions(SolrQuery query, Units units) {
        for (int i = 0; i < units.included().size(); i++) {
            query.removeFilterQuery(join(INCLUDE_PREFIX, i));
            query.remove(INCLUDE_PREFIX + i);
        }
    }

    /** Drops every document in which some page contains a negated unit. */
    static void applyExclusions(SolrQuery query, Units units, String field) {
        if (units == null) return;
        for (int i = 0; i < units.excluded().size(); i++) {
            query.set(EXCLUDE_PREFIX + i, subQuery(field, units.excluded().get(i)));
            query.addFilterQuery("-" + join(EXCLUDE_PREFIX, i));
        }
    }

    private static String subQuery(String field, String unit) {
        return "{!lucene q.op=AND df=" + field + "}" + unit;
    }

    private static String join(String prefix, int i) {
        return "{!join from=parent_id to=parent_id v=$" + prefix + i + "}";
    }

    private static String escapeKeepingWildcards(String word) {
        StringBuilder sb = new StringBuilder();
        for (char c : word.toCharArray()) {
            sb.append(c == '*' || c == '?' ? String.valueOf(c) : ClientUtils.escapeQueryChars(String.valueOf(c)));
        }
        return sb.toString();
    }
}
