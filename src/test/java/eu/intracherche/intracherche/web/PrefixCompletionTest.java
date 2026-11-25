package eu.intracherche.intracherche.web;

import eu.intracherche.intracherche.web.PrefixCompletion.Prefix;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.util.NamedList;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PrefixCompletionTest {

    @Test
    void the_word_being_typed_is_the_last_plain_word_with_no_space_after_it() {
        assertThat(PrefixCompletion.lastWord("suspect affaire tav")).contains(new Prefix(16, "", "tav"));
        assertThat(PrefixCompletion.lastWord("police -fauvil")).contains(new Prefix(7, "-", "fauvil"));
        assertThat(PrefixCompletion.lastWord("l'aff")).contains(new Prefix(0, "", "l'aff"));
        // Inside a phrase still open: a phrase searches the same exact words.
        assertThat(PrefixCompletion.lastWord("\"marchia pol")).contains(new Prefix(9, "", "pol"));
    }

    @Test
    void no_completion_after_a_space_for_short_words_or_for_syntax() {
        assertThat(PrefixCompletion.lastWord("fauvil ")).isEmpty();
        assertThat(PrefixCompletion.lastWord("affaire go")).isEmpty();
        assertThat(PrefixCompletion.lastWord("\"pol")).isEmpty();
        assertThat(PrefixCompletion.lastWord("path:gob")).isEmpty();
        assertThat(PrefixCompletion.lastWord("march*")).isEmpty();
        assertThat(PrefixCompletion.lastWord("eau AND")).isEmpty();
        assertThat(PrefixCompletion.lastWord("")).isEmpty();
        assertThat(PrefixCompletion.lastWord(null)).isEmpty();
    }

    @Test
    void candidates_are_asked_by_folded_prefix_from_one_core() {
        var request = PrefixCompletion.termsRequest(new Prefix(0, "", "Étud"));

        assertThat(request.get("qt")).isEqualTo("/terms");
        assertThat(request.get("terms.fl")).isEqualTo("content_exact");
        assertThat(request.get("terms.prefix")).isEqualTo("etud");
        assertThat(request.get("distrib")).isEqualTo("false");
        assertThat(PrefixCompletion.fold("Cœur")).isEqualTo("coeur");
    }

    @Test
    void the_count_request_counts_each_candidate_as_an_exact_word_under_the_filters() {
        var request = PrefixCompletion.countRequest(List.of("marche", "marchiha"),
                new String[]{"accessTokens:(\"S-1-1-0\")"}, "localhost:8983/solr/A,localhost:8983/solr/B");

        assertThat(request.getFacetQuery()).containsExactly(
                "{!term f=content_exact v=$tw0}", "{!term f=content_exact v=$tw1}");
        assertThat(request.get("tw1")).isEqualTo("marchiha");
        assertThat(request.getFilterQueries()).containsExactly("accessTokens:(\"S-1-1-0\")");
        assertThat(request.get("shards")).isEqualTo("localhost:8983/solr/A,localhost:8983/solr/B");
        assertThat(request.getRows()).isZero();
    }

    @Test
    void only_readable_words_are_offered_ranked_by_readable_pages() {
        List<String> candidates = List.of("marches", "marche", "marchiha", "marchon", "march");
        // Index-wide, "marches" is the most frequent; on the pages this user reads, "marchon" is.
        QueryResponse counts = counts(Map.of(0, 2, 1, 2, 2, 0, 3, 7, 4, 9));

        assertThat(PrefixCompletion.readable(counts, candidates, new Prefix(0, "", "march")))
                .containsExactly("marchon", "marche", "marches");
    }

    @Test
    void at_most_five_are_offered() {
        List<String> candidates = List.of("aaa1", "aaa2", "aaa3", "aaa4", "aaa5", "aaa6");
        QueryResponse counts = counts(Map.of(0, 1, 1, 1, 2, 1, 3, 1, 4, 1, 5, 1));

        assertThat(PrefixCompletion.readable(counts, candidates, new Prefix(0, "", "aaa"))).hasSize(5);
    }

    @Test
    void the_completion_keeps_what_was_typed_and_adds_the_rest() {
        assertThat(PrefixCompletion.complete("suspect affaire Tav", new Prefix(16, "", "Tav"), "tavenna"))
                .isEqualTo("suspect affaire Tavenna");
        assertThat(PrefixCompletion.complete("police -MARCH", new Prefix(7, "-", "MARCH"), "marchiha"))
                .isEqualTo("police -MARCHIHA");
        assertThat(PrefixCompletion.complete("étud", new Prefix(0, "", "étud"), "etudiant"))
                .isEqualTo("étudiant");
    }

    /** A count response: facet query i found {@code counts.get(i)} pages. */
    private static QueryResponse counts(Map<Integer, Integer> counts) {
        NamedList<Object> facetQueries = new NamedList<>();
        counts.forEach((i, count) -> facetQueries.add("{!term f=content_exact v=$tw" + i + "}", count));
        NamedList<Object> facetCounts = new NamedList<>();
        facetCounts.add("facet_queries", facetQueries);
        NamedList<Object> response = new NamedList<>();
        response.add("facet_counts", facetCounts);
        QueryResponse queryResponse = new QueryResponse();
        queryResponse.setResponse(response);
        return queryResponse;
    }
}
