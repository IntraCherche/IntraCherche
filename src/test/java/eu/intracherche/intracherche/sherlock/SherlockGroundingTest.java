package eu.intracherche.intracherche.sherlock;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The pure parts of the fix for an answer that gave one man's life story as another's
 * (Sept. 2026): a page describing René came back for "Je parlais de Maxime Roumagnac", and
 * the fragment handed to the model held only "il", René's name falling outside the window.
 */
class SherlockGroundingTest {

    // Page 8 of "Tiré à part … gestion pas si simple", shortened but in its real order: the
    // sentence naming René sits ~500 characters after the facts a fragment would cut around.
    private static final String PAGE = "L'objet du délit : la coupe rase avec ses andains. "
            + "Marie-Jeanne aime bien Sylvestre, il semble en savoir beaucoup sur la forêt. "
            + "ancien éleveur de vaches limousines, il produisait des « broutards ». "
            + "Maintenant à la retraite, il n'a gardé qu'une laitière et un petit élevage de "
            + "fauves de Bourgogne, une race de lapins qu'il aime bien. Veuf et sans enfants, "
            + "il a loué ses terres. Il vient de vendre les épicéas de la parcelle voisine, ce "
            + "qui va lui permettre de refaire sa toiture et d'améliorer sa maigre pension. "
            + "Marie-Jeanne s'aperçoit qu'elle converse avec le propriétaire de la plantation. "
            + "René, paysan du cru, lui raconte qu'il a été sidéré la semaine dernière.";

    @Test
    void a_passage_that_never_names_the_subject_is_not_grounded() {
        String fragment = PAGE.substring(PAGE.indexOf("Maintenant"), PAGE.indexOf("Marie-Jeanne s'aperçoit"));

        assertThat(LlmService.mentionsAny(fragment, List.of("Maxime Roumagnac"))).isFalse();
        assertThat(LlmService.mentionsAny(PAGE, List.of("René"))).isTrue();
    }

    @Test
    void grounding_ignores_case_accents_hyphens_and_apostrophes() {
        assertThat(LlmService.mentionsAny("la commune de Saint-Michel-de-Maurienne", List.of("saint michel de maurienne"))).isTrue();
        assertThat(LlmService.mentionsAny("L'ÉLEVAGE de lapins", List.of("elevage"))).isTrue();
        assertThat(LlmService.mentionsAny("l’épicéa de la parcelle", List.of("épicéa"))).isTrue();
        assertThat(LlmService.mentionsAny("une page sans rapport", List.of("Roumagnac", "Durand"))).isFalse();
    }

    @Test
    void with_no_terms_to_look_for_nothing_is_flagged() {
        // The mark warns; it must never condemn a passage because expansion returned nothing.
        assertThat(LlmService.mentionsAny("n'importe quel texte", List.of())).isTrue();
        assertThat(LlmService.mentionsAny("n'importe quel texte", null)).isTrue();
    }

    @Test
    void a_page_that_fits_the_budget_is_sent_whole_so_it_keeps_its_subject() {
        String anchor = "fauves de Bourgogne, une race de lapins";

        String context = SherlockRetriever.contextFor(PAGE, anchor, 2500);

        assertThat(context).isEqualTo(PAGE);
        assertThat(context).contains("René");
    }

    @Test
    void a_long_page_is_cut_to_one_window_around_the_match_never_spliced() {
        String anchor = "fauves de Bourgogne, une race de lapins";

        String context = SherlockRetriever.contextFor(PAGE, anchor, 300);

        assertThat(context).contains("fauves de Bourgogne");
        assertThat(context).doesNotContain(" … ");
        assertThat(context.replace("[…] ", "").replace(" […]", "").strip())
                .as("the window is a run of the page, not a montage of it")
                .isSubstringOf(PAGE);
    }

    @Test
    void a_window_never_opens_or_closes_mid_word() {
        String context = SherlockRetriever.contextFor(PAGE, "Veuf et sans enfants", 200);

        String body = context.replace("[…] ", "").replace(" […]", "");
        assertThat(PAGE).contains(body);
        assertThat(body).doesNotStartWith(" ").doesNotEndWith(" ");
        int start = PAGE.indexOf(body);
        if (start > 0) assertThat(PAGE.charAt(start - 1)).isEqualTo(' ');
    }

    @Test
    void a_page_with_no_usable_anchor_falls_back_to_its_opening() {
        // The head of a page carries its heading, which is what says what the page is about.
        String context = SherlockRetriever.contextFor(PAGE, "texte absent de la page", 200);

        assertThat(context).startsWith("L'objet du délit");
        assertThat(context).endsWith("[…]");
    }

    @Test
    void highlight_markup_never_reaches_the_model() {
        assertThat(SherlockRetriever.stripTags("les <em>épicéas</em> de la parcelle"))
                .isEqualTo("les épicéas de la parcelle");
    }

    @Test
    void every_word_of_an_entity_is_required_and_none_is_quoted() {
        // Requiring the entity as a phrase loses it to OCR: the scanned article reads
        // "CarMaxime Roumagnac", so the bigram does not exist in the index at all.
        assertThat(SherlockRetriever.bm25Query(List.of("Maxime Roumagnac", "élevage")))
                .isEqualTo("Maxime Roumagnac élevage");
        assertThat(SherlockRetriever.bm25Query(List.of("13 novembre 2015")))
                .isEqualTo("13 novembre 2015");
        assertThat(SherlockRetriever.bm25Query(Arrays.asList(" ", null, "budget")))
                .isEqualTo("budget");
        assertThat(SherlockRetriever.bm25Query(List.of())).isEmpty();
    }

    @Test
    void adjacency_is_a_boost_not_a_requirement() {
        assertThat(SherlockRetriever.phraseBoosts(List.of("Maxime Roumagnac", "élevage")))
                .as("only the multi-word entity is boosted, and the single word is left alone")
                .isEqualTo("content:\"Maxime Roumagnac\"^3");
        assertThat(SherlockRetriever.phraseBoosts(List.of("budget"))).isEmpty();
    }

    @Test
    void a_keyword_can_never_inject_query_syntax() {
        assertThat(SherlockRetriever.bm25Query(List.of("*:*")))
                .as("every term is escaped")
                .isEqualTo("\\*\\:\\*");
        assertThat(SherlockRetriever.bm25Query(List.of("a\" OR b:c")))
                .isEqualTo("a\\\" OR b\\:c");
        assertThat(SherlockRetriever.phraseBoosts(List.of("a\" OR b:\"c")))
                .as("a boost escapes the quote that would close it")
                .isEqualTo("content:\"a\\\" OR b:\\\"c\"^3");
    }

    @Test
    void a_rewritten_question_replaces_the_follow_up_unless_it_runs_away() {
        assertThat(LlmService.searchQuestion("Je parlais de Maxime Roumagnac", "Qui est Maxime Roumagnac ?"))
                .isEqualTo("Qui est Maxime Roumagnac ?");
        assertThat(LlmService.searchQuestion("Je parlais de Maxime Roumagnac", "  "))
                .isEqualTo("Je parlais de Maxime Roumagnac");
        assertThat(LlmService.searchQuestion("Et lui ?", null)).isEqualTo("Et lui ?");
        // The model answering instead of restating: that text must not become the search query.
        assertThat(LlmService.searchQuestion("Et lui ?", "x".repeat(400))).isEqualTo("Et lui ?");
    }

    @Test
    void a_correction_replaces_whole_words_in_the_case_they_were_typed() {
        java.util.Map<String, String> fix = java.util.Map.of("Ravena", "tavenna");

        assertThat(SherlockRetriever.applyCorrections("Qui est le suspect dans l'affaire Ravena ?", fix))
                .isEqualTo("Qui est le suspect dans l'affaire Tavenna ?");
        assertThat(SherlockRetriever.applyCorrections("Ravena, Ravenas", fix)).isEqualTo("Tavenna, Ravenas");
        assertThat(SherlockRetriever.applyCorrections("rien à corriger", java.util.Map.of())).isEqualTo("rien à corriger");
    }

    @Test
    void a_correction_may_not_turn_a_word_into_another_kind_of_word() {
        assertThat(SherlockRetriever.changesKind("Lyana", "l'an")).isTrue();
        assertThat(SherlockRetriever.changesKind("l'ENOB", "l'eno2")).isTrue();
        assertThat(SherlockRetriever.changesKind("Fauvilet", "fauvillet")).isFalse();
        assertThat(SherlockRetriever.changesKind("l'afaire", "l'affaire")).isFalse();
    }

    @Test
    void a_long_page_is_cut_into_overlapping_windows_that_cover_it_all() {
        String page = "mot ".repeat(1000).strip();   // 3 999 characters

        List<String> windows = SherlockRetriever.pageWindows(page, 1800);

        assertThat(windows).hasSizeGreaterThan(2);
        assertThat(windows).allSatisfy(w -> assertThat(w.replace("[…] ", "").replace(" […]", "").length()).isLessThanOrEqualTo(1800));
        assertThat(windows.get(0)).doesNotStartWith("[…]");
        assertThat(windows.get(windows.size() - 1)).doesNotEndWith("[…]");
        assertThat(SherlockRetriever.pageWindows("court", 1800)).containsExactly("court");
    }

    @Test
    void a_keyword_must_come_from_the_question() {
        String q = "Combien coûte le transport d'un chien en taxi dans la Manche ?";

        assertThat(LlmService.grounded("taxi", q)).isTrue();
        assertThat(LlmService.grounded("Manche", q)).isTrue();
        assertThat(LlmService.grounded("chiens", q)).isFalse();
        assertThat(LlmService.grounded("Stade de France", q)).isFalse();   // copied from the prompt
        // A stem of a question word is still the question's: the model shortens "gendarmes" to "gendarme"
        assertThat(LlmService.grounded("gendarme", "Quelle part du temps des gendarmes ?")).isTrue();
    }
}
