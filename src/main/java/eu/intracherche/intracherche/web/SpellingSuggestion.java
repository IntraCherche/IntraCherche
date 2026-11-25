package eu.intracherche.intracherche.web;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrRequest;
import org.apache.solr.client.solrj.request.QueryRequest;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.client.solrj.response.SpellCheckResponse;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * "Vouliez-vous dire…": the query with its misspelled words corrected, for a search whose words
 * found nothing together.
 *
 * <p>Solr's spellchecker draws its words from the whole index, including documents the user
 * cannot open, and ignores fq when it proposes them. Its raw suggestions would show a user
 * that "MARCHIHA" is written somewhere they have no access to. Only collations are used: the
 * {@code /spell} handler verifies each one by running it with the request's filters, the
 * accessTokens filter among them, and keeps it only if it finds a page.
 *
 * <p>Words are corrected one at a time, each verified alone. Verifying the whole corrected
 * query asked for one readable page holding every word: "suspect affaire tavenna" got no
 * correction because no page has all three, though "tavenna" alone is on 46. A single word
 * that finds a readable page is a word the user may see, whatever the rest of the query says,
 * so the query around it may use any syntax: only its plain words are corrected, the rest is
 * left as typed.
 *
 * <p>Only words that find no readable page are corrected: one request counts them all, across
 * the cores, before any spellchecking. Each correction is then asked of each core on its own.
 * Across shards, Solr merges each core's best collation without comparing them, and the
 * 38 000-page Wikipedia core's "gabilou" beat the demo core's "fauvillet" for "fauvilet". Each
 * core's collation is Solr's best by edit distance and word frequency within that core;
 * between cores, the closest to what was typed wins, then the one with more hits, which are
 * also counted under the user's filters.
 */
public final class SpellingSuggestion {

    private static final Logger log = LoggerFactory.getLogger(SpellingSuggestion.class);

    static final String HANDLER = "/spell";
    /** The spellchecker's minQueryLength: shorter words are never corrected. */
    static final int MIN_WORD_LENGTH = 4;
    /** Words checked per query, so a pasted paragraph costs no more than a few requests. */
    static final int MAX_WORDS = 8;

    /** A word, possibly with inner apostrophes or hyphens: "l'affaire", "saint-michel". */
    private static final Pattern PLAIN_WORD = Pattern.compile("[\\p{L}\\p{N}]+(?:['’-][\\p{L}\\p{N}]+)*");
    /** A whitespace-separated token: an optional + or - and what follows it. */
    private static final Pattern TOKEN = Pattern.compile("([+-]?)(\\S+)");
    /** Plain words the lucene parser reads as operators. */
    private static final Set<String> OPERATORS = Set.of("AND", "OR", "NOT");

    record Collation(String query, long hits) {}

    private SpellingSuggestion() {}

    /**
     * The words of {@code q} a correction may replace: plain words, bare or after + or -, in
     * order and without repeats. Phrases, wildcards, field clauses and the like are left out.
     */
    static List<String> words(String q) {
        return words(q, MAX_WORDS);
    }

    /** {@link #words(String)} with another cap: a question to Sherlock is a sentence, not a query. */
    public static List<String> words(String q, int maxWords) {
        if (q == null) return List.of();
        Set<String> words = new LinkedHashSet<>();
        Matcher token = TOKEN.matcher(q);
        while (token.find() && words.size() < maxWords) {
            String word = token.group(2);
            if (PLAIN_WORD.matcher(word).matches() && !OPERATORS.contains(word)
                    && word.length() >= MIN_WORD_LENGTH) {
                words.add(word);
            }
        }
        return List.copyOf(words);
    }

    /**
     * The corrections for those of {@code words} that are on no page the user can read, word by
     * word: typed word to corrected word. {@code filterQueries} must carry the access filter.
     * Best effort: a failed request only costs the corrections it would have given.
     */
    public static Map<String, String> corrections(SolrClient solr, List<String> words, String[] filterQueries,
                                                  String shards, List<String> cores) {
        List<String> unmatched = unmatched(solr, words, filterQueries, shards, cores);
        Map<String, String> corrections = new java.util.LinkedHashMap<>();
        for (String word : unmatched) {
            List<Collation> collations = new ArrayList<>();
            for (String core : cores) {
                try {
                    QueryResponse spellcheck = new QueryRequest(request(word, filterQueries), SolrRequest.METHOD.POST)
                            .process(solr, core);
                    collation(spellcheck).ifPresent(collations::add);
                } catch (Exception e) {
                    log.warn("Spellcheck failed on core {} ({}), no suggestion from it", core, e.getMessage());
                }
            }
            best(word, collations).ifPresent(correction -> corrections.put(word, correction));
        }
        return corrections;
    }

    /**
     * Those of {@code words} on no page the user can read. Empty when the count fails: a word
     * that cannot be checked is never corrected.
     */
    public static List<String> unmatched(SolrClient solr, List<String> words, String[] filterQueries,
                                         String shards, List<String> cores) {
        if (words.isEmpty() || cores.isEmpty()) return List.of();
        try {
            QueryResponse counts = new QueryRequest(countRequest(words, filterQueries, shards), SolrRequest.METHOD.POST)
                    .process(solr, cores.get(0));
            return unmatched(counts, words);
        } catch (Exception e) {
            log.warn("Counting the query's words failed ({}), no spelling suggestion", e.getMessage());
            return List.of();
        }
    }

    /**
     * Every verified collation for one word, from every core, closest first: up to
     * {@code perCore} from each. Each was run by Solr under {@code filterQueries} and found a
     * page, so none names a word the user cannot read.
     */
    public static List<String> candidates(SolrClient solr, String word, String[] filterQueries,
                                          List<String> cores, int perCore) {
        String typed = fold(word);
        Set<String> found = new LinkedHashSet<>();
        for (String core : cores) {
            try {
                SolrQuery query = request(word, filterQueries);
                query.setParam("spellcheck.maxCollations", String.valueOf(perCore));
                SpellCheckResponse spellcheck = new QueryRequest(query, SolrRequest.METHOD.POST)
                        .process(solr, core).getSpellCheckResponse();
                if (spellcheck == null || spellcheck.getCollatedResults() == null) continue;
                spellcheck.getCollatedResults().stream()
                        .filter(c -> c.getNumberOfHits() > 0)
                        .map(SpellCheckResponse.Collation::getCollationQueryString)
                        .filter(c -> !fold(c).equals(typed))
                        .forEach(found::add);
            } catch (Exception e) {
                log.warn("Spellcheck failed on core {} ({}), no candidate from it", core, e.getMessage());
            }
        }
        return found.stream().sorted(Comparator.comparingDouble((String c) -> -similarity(typed, fold(c)))).toList();
    }

    /**
     * One request counting, for each word, the pages the user can read that hold it, under
     * the search's filters and across its cores.
     */
    static SolrQuery countRequest(List<String> words, String[] filterQueries, String shards) {
        SolrQuery query = new SolrQuery("*:*");
        query.setRows(0);
        query.setFacet(true);
        for (int i = 0; i < words.size(); i++) {
            // The word travels as a parameter, not inside the local params.
            query.addFacetQuery("{!lucene df=" + SearchController.CONTENT_FIELD_NAME + " q.op=AND v=$sw" + i + "}");
            query.setParam("sw" + i, words.get(i));
        }
        if (filterQueries != null) Arrays.stream(filterQueries).forEach(query::addFilterQuery);
        if (shards != null) {
            query.setParam("shards", shards);
            query.setParam("distrib", "true");
        }
        return query;
    }

    /** The words the count request found on no readable page. */
    static List<String> unmatched(QueryResponse counts, List<String> words) {
        Map<String, Integer> byQuery = counts.getFacetQuery();
        List<String> unmatched = new ArrayList<>();
        for (int i = 0; i < words.size(); i++) {
            Integer count = byQuery == null ? null : byQuery.get(
                    "{!lucene df=" + SearchController.CONTENT_FIELD_NAME + " q.op=AND v=$sw" + i + "}");
            if (count != null && count == 0) unmatched.add(words.get(i));
        }
        return unmatched;
    }

    /** The spellcheck request for one word on one core, under the search's filters. No shards. */
    static SolrQuery request(String word, String[] filterQueries) {
        SolrQuery query = new SolrQuery(word);
        query.setRequestHandler(HANDLER);
        query.setParam("df", SearchController.CONTENT_FIELD_NAME);
        query.setParam("q.op", "AND");
        query.setRows(0);
        query.setParam("spellcheck.collateExtendedResults", "true");
        if (filterQueries != null) Arrays.stream(filterQueries).forEach(query::addFilterQuery);
        return query;
    }

    /** The core's best verified collation. Reads collations only, never the raw suggestions. */
    static Optional<Collation> collation(QueryResponse response) {
        SpellCheckResponse spellcheck = response.getSpellCheckResponse();
        if (spellcheck == null || spellcheck.getCollatedResults() == null) return Optional.empty();
        return spellcheck.getCollatedResults().stream()
                .filter(c -> c.getNumberOfHits() > 0)
                .findFirst()
                .map(c -> new Collation(c.getCollationQueryString(), c.getNumberOfHits()));
    }

    /** Of the cores' collations for one word, the closest to it, then the one with more hits. */
    static Optional<String> best(String word, List<Collation> collations) {
        String typed = fold(word);
        return collations.stream()
                .filter(c -> !fold(c.query()).equals(typed))
                .max(Comparator.<Collation>comparingDouble(c -> similarity(typed, fold(c.query())))
                        .thenComparingLong(Collation::hits))
                .map(Collation::query);
    }

    /**
     * {@code q} with each corrected word replaced where it stands alone, keeping its + or - and
     * its capitals: the collation comes back folded like the index ("marchiha" for "MARCHIA").
     */
    static String rewrite(String q, Map<String, String> corrections) {
        Matcher token = TOKEN.matcher(q);
        StringBuilder rewritten = new StringBuilder();
        while (token.find()) {
            String correction = corrections.get(token.group(2));
            String replacement = correction == null ? token.group()
                    : token.group(1) + matchCase(token.group(2), correction);
            token.appendReplacement(rewritten, Matcher.quoteReplacement(replacement));
        }
        token.appendTail(rewritten);
        return rewritten.toString();
    }

    /** {@code correction} in the case {@code typed} was written in: all capitals, capitalized, or as is. */
    public static String matchCase(String typed, String correction) {
        boolean hasLetter = typed.chars().anyMatch(Character::isLetter);
        if (hasLetter && typed.equals(typed.toUpperCase(Locale.ROOT)) && typed.length() > 1) {
            return correction.toUpperCase(Locale.ROOT);
        }
        if (Character.isUpperCase(typed.charAt(0))) {
            return Character.toUpperCase(correction.charAt(0)) + correction.substring(1);
        }
        return correction;
    }

    /** Lower case without accents: the collation's corrected words come back folded like the index. */
    public static String fold(String s) {
        String decomposed = Normalizer.normalize(s.trim(), Normalizer.Form.NFD);
        return decomposed.replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    /** 1 - edit distance / length of the longer string, as Solr's own ranking measures closeness. */
    public static double similarity(String a, String b) {
        int max = Math.max(a.length(), b.length());
        return max == 0 ? 1 : 1 - (double) levenshtein(a, b) / max;
    }

    private static int levenshtein(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) previous[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int substitution = previous[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(substitution, Math.min(previous[j] + 1, current[j - 1] + 1));
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }
}
