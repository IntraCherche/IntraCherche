package eu.intracherche.intracherche.indexing.parser;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PrivateUseGlyphsTest {

    @Test
    void libertine_ligatures_become_their_letters() {
        assertThat(PrivateUseGlyphs.replacement("DBXEVT+LinLibertineO", 0xE03C)).isEqualTo("tt");
        assertThat(PrivateUseGlyphs.replacement("ABCDEF+LinLibertineOI", 0xE03C)).isEqualTo("tt");
        assertThat(PrivateUseGlyphs.replacement("DBXEVT+LinLibertineO", 0xE048)).isEqualTo("Qu");
    }

    @Test
    void a_code_point_means_nothing_outside_its_font() {
        // Another font may draw anything at U+E03C: guessing would corrupt its text.
        assertThat(PrivateUseGlyphs.replacement("ABCDEF+SomeOtherFont", 0xE03C)).isNull();
        assertThat(PrivateUseGlyphs.replacement(null, 0xE03C)).isNull();
    }

    @Test
    void an_unknown_libertine_glyph_is_left_alone() {
        // The small-caps century numerals ("·e siècle") cannot be told apart from the corpus.
        assertThat(PrivateUseGlyphs.replacement("LinLibertineO", 0xE068)).isNull();
    }

    @Test
    void pictograms_become_spaces_so_they_never_stick_to_a_word() {
        assertThat(PrivateUseGlyphs.replacement("SymbolMT", 0xF0B7)).isEqualTo(" ");
        assertThat(PrivateUseGlyphs.replacement("Wingdings 2", 0xF083)).isEqualTo(" ");
        assertThat(PrivateUseGlyphs.replacement("OpenSymbol", 0xF0A7)).isEqualTo(" ");
        assertThat(PrivateUseGlyphs.replacement("LinLibertineO", 0xF0B7)).isNull();
    }
}
