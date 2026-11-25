package eu.intracherche.intracherche.web;

import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.client.solrj.response.TermsResponse;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Completions of the word being typed: "march" → "marchiha", drawn from the pages the user can read.
 *
 * <p>The terms component lists the index's words by prefix quickly, but from the whole index:
 * it knows nothing of fq, so it would show words from documents the user cannot open. Its
 * words are only candidates. One request then counts, for each of them, the pages the user can
 * read that hold it, under the search's filters and across its cores, and only words found on
 * such a page are offered. They are ranked by that count, never by the index-wide frequency the
 * terms component sorts them by, so the order tells nothing of the documents left out.
 *
 * <p>The candidates are the most frequent words of each core with that prefix, so a word
 * readable only on a rare page may miss the cut behind words the user cannot read: a
 * completion missed, never a word shown. A longer prefix narrows the candidates and brings it back.
 *
 * <p>Counted on content_exact, as the exact indexed word: content is indexed through
 * EdgeNGram, where a candidate "marche" would count a readable page holding only "marches".
 * The words come back as indexed, folded ("attribuee"): the letters typed are kept as typed,
 * and only the rest is added.
 */
final class PrefixCompletion {

    static final String HANDLER = "/terms";
    static final String FIELD = "content_exact";
    /** Shorter prefixes complete to little more than the most common words. */
    static final int MIN_PREFIX_LENGTH = 3;
    /** Candidates taken from each core, then from all of them together. */
    static final int CANDIDATES_PER_CORE = 50;
    static final int MAX_CANDIDATES = 100;
    static final int SHOWN = 5;

    /** A word in progress, possibly with inner apostrophes or hyphens: "l'aff", "saint-mi". */
    private static final Pattern PLAIN_WORD = Pattern.compile("[\\p{L}\\p{N}]+(?:['’-][\\p{L}\\p{N}]+)*");
    /** The last whitespace-separated token, at the very end of the query: an optional + or - and the word. */
    private static final Pattern LAST_TOKEN = Pattern.compile("([+-]?)(\\S+)$");
    private static final List<String> OPERATORS = List.of("AND", "OR", "NOT");

    /** The word being typed: where it starts in the query, the + or - before it, and the word. */
    record Prefix(int start, String sign, String typed) {
        String folded() { return fold(typed); }
    }

    private PrefixCompletion() {}

    /**
     * The word being typed at the end of {@code q}: a plain word of at least
     * {@link #MIN_PREFIX_LENGTH} letters, bare or after + or -. None once a space follows it,
     * or for phrases, wildcards, field clauses and the like.
     */
    static Optional<Prefix> lastWord(String q) {
        if (q == null) return Optional.empty();
        Matcher token = LAST_TOKEN.matcher(q);
        if (!token.find()) return Optional.empty();
        String word = token.group(2);
        if (!PLAIN_WORD.matcher(word).matches() || OPERATORS.contains(word)
                || word.length() < MIN_PREFIX_LENGTH) {
            return Optional.empty();
        }
        return Optional.of(new Prefix(token.start(), token.group(1), word));
    }

    /** The terms request for one core: its most frequent words starting with the prefix. */
    static SolrQuery termsRequest(Prefix prefix) {
        SolrQuery query = new SolrQuery();
        query.setRequestHandler(HANDLER);
        query.setParam("terms", "true");
        query.setParam("terms.fl", FIELD);
        query.setParam("terms.prefix", prefix.folded());
        query.setParam("terms.limit", String.valueOf(CANDIDATES_PER_CORE));
        query.setParam("terms.sort", "count");
        query.setParam("distrib", "false");
        return query;
    }

    /** The words a core's terms response lists. */
    static List<String> terms(QueryResponse response) {
        TermsResponse terms = response.getTermsResponse();
        if (terms == null || terms.getTerms(FIELD) == null) return List.of();
        return terms.getTerms(FIELD).stream().map(TermsResponse.Term::getTerm).toList();
    }

    /**
     * One request counting, for each candidate, the pages the user can read that hold it as
     * a word, under the search's filters and across its cores.
     */
    static SolrQuery countRequest(List<String> candidates, String[] filterQueries, String shards) {
        SolrQuery query = new SolrQuery("*:*");
        query.setRows(0);
        query.setFacet(true);
        for (int i = 0; i < candidates.size(); i++) {
            // The word travels as a parameter, not inside the local params.
            query.addFacetQuery(countKey(i));
            query.setParam("tw" + i, candidates.get(i));
        }
        if (filterQueries != null) Arrays.stream(filterQueries).forEach(query::addFilterQuery);
        if (shards != null) {
            query.setParam("shards", shards);
            query.setParam("distrib", "true");
        }
        return query;
    }

    private static String countKey(int i) {
        return "{!term f=" + FIELD + " v=$tw" + i + "}";
    }

    /**
     * The candidates found on a page the user can read, most such pages first, at most
     * {@link #SHOWN}. The word already typed in full is no completion.
     */
    static List<String> readable(QueryResponse counts, List<String> candidates, Prefix prefix) {
        Map<String, Integer> byQuery = counts.getFacetQuery();
        if (byQuery == null) return List.of();
        record Counted(String term, int count) {}
        List<Counted> found = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            Integer count = byQuery.get(countKey(i));
            if (count != null && count > 0 && !candidates.get(i).equals(prefix.folded())) {
                found.add(new Counted(candidates.get(i), count));
            }
        }
        return found.stream()
                .sorted(Comparator.comparingInt(Counted::count).reversed().thenComparing(Counted::term))
                .limit(SHOWN)
                .map(Counted::term)
                .toList();
    }

    /**
     * {@code q} with the word being typed completed to {@code term}: the letters typed stay as
     * typed, the rest is added, in capitals if the word was typed in capitals.
     */
    static String complete(String q, Prefix prefix, String term) {
        String head = q.substring(0, prefix.start()) + prefix.sign();
        String folded = prefix.folded();
        if (!term.startsWith(folded)) return head + term;
        String rest = term.substring(folded.length());
        String typed = prefix.typed();
        boolean capitals = typed.chars().anyMatch(Character::isLetter)
                && typed.equals(typed.toUpperCase(Locale.ROOT));
        return head + typed + (capitals ? rest.toUpperCase(Locale.ROOT) : rest);
    }

    /**
     * Folded like content_exact's ICU folding, for the letters a French user types: no
     * accents, lower case, and the ligatures spelled out.
     */
    static String fold(String s) {
        return SpellingSuggestion.fold(s)
                .replace("œ", "oe").replace("æ", "ae").replace("ß", "ss");
    }
}
