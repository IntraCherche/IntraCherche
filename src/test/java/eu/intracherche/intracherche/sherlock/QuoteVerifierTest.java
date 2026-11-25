package eu.intracherche.intracherche.sherlock;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class QuoteVerifierTest {

    private static final List<SherlockResult> PASSAGES = List.of(
            passage("rapport.pdf", 8, "JB, identifié comme mis en cause dans trois procé-\ndures, a été interpellé le 30 mai 2026 puis mis en examen le 1er juin."),
            passage("note.pdf", 2, "Le marché a été attribué le 12 mars à l'entreprise Durand."));

    private static SherlockResult passage(String file, int page, String context) {
        return new SherlockResult(file + page, "/share/" + file, file, "", context, page);
    }

    private static SherlockQuote only(String answer) {
        List<SherlockQuote> quotes = QuoteVerifier.verify(answer, PASSAGES);
        assertThat(quotes).hasSize(1);
        return quotes.get(0);
    }

    @Test
    void a_quotation_in_the_passage_it_cites_is_verified() {
        String answer = "Le suspect est JB : « a été interpellé le 30 mai 2026 » [DOCUMENT 1 - rapport.pdf, page 8].";

        SherlockQuote q = only(answer);

        assertThat(q.verified()).isTrue();
        assertThat(q.document()).isEqualTo(1);
        assertThat(answer.substring(q.start(), q.end())).isEqualTo("« a été interpellé le 30 mai 2026 »");
    }

    @Test
    void case_accents_punctuation_and_line_break_hyphens_do_not_matter() {
        assertThat(only("« JB, identifie comme mis en cause dans trois procédures » [DOCUMENT 1 - rapport.pdf]").verified()).isTrue();
        assertThat(only("« Le marché a été attribué le 12 mars à l’entreprise Durand. » [DOCUMENT 2 - note.pdf]").verified()).isTrue();
    }

    @Test
    void a_changed_word_is_not_verified() {
        SherlockQuote q = only("« a été arrêté le 30 mai 2026 » [DOCUMENT 1 - rapport.pdf]");

        assertThat(q.verified()).isFalse();
        assertThat(q.foundIn()).isNull();
    }

    @Test
    void ocr_spacing_is_ignored_but_not_a_changed_letter() {
        List<SherlockResult> ocr = List.of(passage("scan.pdf", 2,
                "Notre Departement a ainsi recemmentfaitl'objetdelapartdelasocieteNotreFamille.com, d'une demande"));

        assertThat(QuoteVerifier.verify("« récemment fait l'objet de la part de la société NotreFamille.com » [DOCUMENT 1 - scan.pdf]", ocr)
                .get(0).verified()).isTrue();
        assertThat(QuoteVerifier.verify("« récemment fait l'objet de la part de la société NotreFamilles.com » [DOCUMENT 1 - scan.pdf]", ocr)
                .get(0).verified()).isFalse();
    }

    @Test
    void a_private_use_glyph_stands_for_the_letters_it_draws() {
        // The Wikipedia PDFs' font draws "tt" with U+E03C.
        List<SherlockResult> wiki = List.of(passage("wiki.pdf", 30, "le bilan des a\uE03Centats est de 130 morts, ce\uE03Ce année-là"));

        assertThat(QuoteVerifier.verify("« le bilan des attentats est de 130 morts, cette année-là » [DOCUMENT 1 - wiki.pdf]", wiki)
                .get(0).verified()).isTrue();
        assertThat(QuoteVerifier.verify("« le bilan des attentats est de 131 morts » [DOCUMENT 1 - wiki.pdf]", wiki)
                .get(0).verified()).isFalse();
    }

    @Test
    void a_bracketed_insertion_or_a_citation_inside_the_quotes_is_a_cut() {
        assertThat(only("« JB [et] a été interpellé le 30 mai 2026 » [DOCUMENT 1 - rapport.pdf]").verified()).isTrue();

        SherlockQuote q = only("« attribué le 12 mars [DOCUMENT 2 - note.pdf] »");
        assertThat(q.document()).isEqualTo(2);
        assertThat(q.verified()).isTrue();
    }

    @Test
    void a_quotation_cited_from_the_wrong_passage_is_not_verified_but_says_where_it_is() {
        SherlockQuote q = only("« attribué le 12 mars » [DOCUMENT 1 - rapport.pdf]");

        assertThat(q.verified()).isFalse();
        assertThat(q.foundIn()).isEqualTo(2);
    }

    @Test
    void an_ellipsis_marks_a_cut_and_the_pieces_must_be_in_order() {
        assertThat(only("« JB … mis en examen le 1er juin » [DOCUMENT 1 - rapport.pdf]").verified()).isTrue();
        assertThat(only("« mis en examen […] JB » [DOCUMENT 1 - rapport.pdf]").verified()).isFalse();
    }

    @Test
    void a_citation_later_in_the_sentence_belongs_to_the_quotation() {
        SherlockQuote q = only("« attribué le 12 mars », indique la note [DOCUMENT 2 - note.pdf].");

        assertThat(q.document()).isEqualTo(2);
        assertThat(q.verified()).isTrue();
    }

    @Test
    void a_citation_in_another_paragraph_does_not() {
        SherlockQuote q = only("Selon la note, « attribué le 12 mars ».\n\nPar ailleurs JB a été interpellé [DOCUMENT 1 - rapport.pdf].");

        assertThat(q.document()).isNull();
        assertThat(q.foundIn()).isEqualTo(2);
        assertThat(q.verified()).isTrue();
    }

    @Test
    void each_quotation_takes_its_own_citation() {
        List<SherlockQuote> quotes = QuoteVerifier.verify(
                "« attribué le 12 mars » et « interpellé le 30 mai » [DOCUMENT 1 - rapport.pdf]", PASSAGES);

        assertThat(quotes).hasSize(2);
        assertThat(quotes.get(0).document()).isNull();
        assertThat(quotes.get(0).verified()).isTrue();
        assertThat(quotes.get(1).document()).isEqualTo(1);
        assertThat(quotes.get(1).verified()).isTrue();
    }

    @Test
    void the_verdict_reaches_the_pane_and_survives_the_database() throws Exception {
        // The pane reads "verified" from the JSON; the DAO stores and reads the same JSON.
        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(List.of(new SherlockQuote(0, 10, 1, 1), new SherlockQuote(12, 20, 1, 2)));

        assertThat(json).contains("\"verified\":true").contains("\"verified\":false");
        List<SherlockQuote> back = mapper.readValue(json, new TypeReference<List<SherlockQuote>>() {});
        assertThat(back).extracting(SherlockQuote::verified).containsExactly(true, false);
    }

    @Test
    void an_answer_without_quotations_has_none() {
        assertThat(QuoteVerifier.verify(LlmService.NOT_FOUND, PASSAGES)).isEmpty();
        assertThat(QuoteVerifier.verify("JB [DOCUMENT 1 - rapport.pdf]", List.of())).isEmpty();
    }
}
