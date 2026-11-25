package eu.intracherche.intracherche.web;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SnippetSelectorTest {

    private static final List<String> TERMS = SnippetSelector.queryTerms("automates Saint Michel de Maurienne");

    @Test
    void query_terms_are_folded_and_operators_dropped() {
        assertThat(SnippetSelector.queryTerms("Régulations AND \"Saint-Michel\""))
                .containsExactly("regulations", "saint", "michel");
    }

    @Test
    void a_lower_ranked_page_showing_a_missing_term_beats_pages_repeating_shown_ones() {
        // The PV-CM-du-02-04-2026.pdf case: context pages outrank the only page naming the subject.
        List<String> snippets = List.of(
                "Convention <b>de</b> mise a disposition",                                   // p24
                "Commune <b>de</b> <b>Saint</b>-<b>Michel</b>-<b>de</b>-<b>Maurienne</b>",      // p23
                "Centre Social <b>de</b> St <b>Michel</b> <b>de</b> <b>Maurienne</b>",           // p11
                "Remplacement des <b>automates</b> <b>de</b> gestion");                        // p2

        assertThat(SnippetSelector.select(snippets, TERMS, 3)).containsExactly(0, 1, 3);
    }

    @Test
    void rank_order_fills_slots_once_every_term_is_shown() {
        List<String> snippets = List.of("<b>automates</b>", "<b>automates</b> encore", "et <b>automates</b>");

        assertThat(SnippetSelector.select(snippets, TERMS, 3)).containsExactly(0, 1, 2);
    }

    @Test
    void highlighted_words_cover_terms_they_start_with_accents_folded() {
        List<String> snippets = List.of("<b>maurienne</b>", "<b>Automatisés</b>", "rien");

        assertThat(SnippetSelector.select(snippets, SnippetSelector.queryTerms("maurienne automat"), 2))
                .containsExactly(0, 1);
    }

    @Test
    void sections_without_snippet_are_never_picked() {
        assertThat(SnippetSelector.select(Arrays.asList(null, "<b>automates</b>", " "), TERMS, 3))
                .containsExactly(1);
    }
}
