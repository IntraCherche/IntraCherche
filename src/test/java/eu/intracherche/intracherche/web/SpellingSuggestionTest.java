package eu.intracherche.intracherche.web;

import eu.intracherche.intracherche.web.SpellingSuggestion.Collation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SpellingSuggestionTest {

    @Test
    void plain_words_are_candidates_whatever_surrounds_them() {
        assertThat(SpellingSuggestion.words("suspect affaire Ravena")).containsExactly("suspect", "affaire", "Ravena");
        assertThat(SpellingSuggestion.words("l'affaire saint-michel 2026")).containsExactly("l'affaire", "saint-michel", "2026");
        assertThat(SpellingSuggestion.words("marchia OR police -fauvilet +sadiracq"))
                .containsExactly("marchia", "police", "fauvilet", "sadiracq");
    }

    @Test
    void syntax_short_words_and_repeats_are_left_out() {
        assertThat(SpellingSuggestion.words("\"marchia police\" march* path:fauvilet tavena~1 (sadiracq) AND NOT"))
                .isEmpty();
        assertThat(SpellingSuggestion.words("le pou du tavena tavena")).containsExactly("tavena");
        assertThat(SpellingSuggestion.words("   ")).isEmpty();
        assertThat(SpellingSuggestion.words(null)).isEmpty();
    }

    @Test
    void at_most_eight_words_are_checked() {
        assertThat(SpellingSuggestion.words("un1a deux trois quatre cinq1 six1 sept huit neuf dix1")).hasSize(8);
    }

    @Test
    void the_count_request_passes_each_word_as_a_parameter_under_the_filters() {
        var request = SpellingSuggestion.countRequest(List.of("suspect", "tavena"),
                new String[]{"accessTokens:(\"S-1-1-0\")"}, "localhost:8983/solr/A,localhost:8983/solr/B");

        assertThat(request.getFacetQuery()).containsExactly(
                "{!lucene df=content q.op=AND v=$sw0}", "{!lucene df=content q.op=AND v=$sw1}");
        assertThat(request.get("sw1")).isEqualTo("tavena");
        assertThat(request.getFilterQueries()).containsExactly("accessTokens:(\"S-1-1-0\")");
        assertThat(request.get("shards")).isEqualTo("localhost:8983/solr/A,localhost:8983/solr/B");
        assertThat(request.getRows()).isZero();
    }

    @Test
    void a_word_is_corrected_alone_on_one_core_under_the_filters() {
        var request = SpellingSuggestion.request("tavena", new String[]{"accessTokens:(\"S-1-1-0\")"});

        assertThat(request.getQuery()).isEqualTo("tavena");
        assertThat(request.get("q.op")).isEqualTo("AND");
        assertThat(request.get("qt")).isEqualTo("/spell");
        assertThat(request.get("shards")).isNull();
        assertThat(request.getFilterQueries()).containsExactly("accessTokens:(\"S-1-1-0\")");
    }

    @Test
    void the_closest_collation_wins_across_cores() {
        // "fauvilet": the demo core's "fauvillet" against the Wikipedia core's "gabilou".
        assertThat(SpellingSuggestion.best("fauvilet",
                List.of(new Collation("gabilou", 1), new Collation("fauvillet", 1))))
                .contains("fauvillet");
        // Closer beats more frequent: "dannes" over "donne".
        assertThat(SpellingSuggestion.best("Danne",
                List.of(new Collation("donne", 5), new Collation("dannes", 1))))
                .contains("dannes");
    }

    @Test
    void equally_close_collations_are_ranked_by_hits() {
        assertThat(SpellingSuggestion.best("marchia",
                List.of(new Collation("marchiba", 1), new Collation("marchiha", 4))))
                .contains("marchiha");
    }

    @Test
    void a_collation_that_only_folds_what_was_typed_is_no_correction() {
        assertThat(SpellingSuggestion.best("Estève", List.of(new Collation("esteve", 3)))).isEmpty();
        assertThat(SpellingSuggestion.best("fauvilet", List.of())).isEmpty();
    }

    @Test
    void only_the_corrected_words_change_in_the_query() {
        assertThat(SpellingSuggestion.rewrite("suspect affaire tavena", Map.of("tavena", "tavenna")))
                .isEqualTo("suspect affaire tavenna");
        assertThat(SpellingSuggestion.rewrite("\"place de Lorient\"  -Sadiracq OR MARCHIA",
                Map.of("Sadiracq", "sadirac", "MARCHIA", "marchiha")))
                .isEqualTo("\"place de Lorient\"  -Sadirac OR MARCHIHA");
        // Inside a phrase or a wildcard the word is not a whole token: left alone.
        assertThat(SpellingSuggestion.rewrite("\"tavena\" tavena*", Map.of("tavena", "tavenna")))
                .isEqualTo("\"tavena\" tavena*");
    }

    @Test
    void the_correction_keeps_the_case_it_was_typed_in() {
        assertThat(SpellingSuggestion.matchCase("MARCHIA", "marchiha")).isEqualTo("MARCHIHA");
        assertThat(SpellingSuggestion.matchCase("Tavena", "tavenna")).isEqualTo("Tavenna");
        assertThat(SpellingSuggestion.matchCase("tavena", "tavenna")).isEqualTo("tavenna");
        assertThat(SpellingSuggestion.matchCase("2026x", "2025x")).isEqualTo("2025x");
    }
}
