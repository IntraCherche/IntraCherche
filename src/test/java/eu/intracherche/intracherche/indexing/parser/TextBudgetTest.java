package eu.intracherche.intracherche.indexing.parser;

import org.apache.tika.metadata.Metadata;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** indexing-config.maxTextChars: a file keeps at most that much text, and says so when cut. */
class TextBudgetTest {

    @Test
    void take_keeps_what_fits_then_nothing() {
        TextBudget budget = new TextBudget(10);

        assertThat(budget.take("abcdef")).isEqualTo("abcdef");
        assertThat(budget.take("ghijkl")).isEqualTo("ghij");
        assertThat(budget.take("mno")).isEmpty();
        assertThat(budget.truncated()).isTrue();
    }

    @Test
    void cap_cuts_sections_to_the_limit_and_leaves_a_small_file_alone() {
        List<IndexedSection> sections = List.of(new IndexedSection("A", "12345"), new IndexedSection("B", "67890"));

        TextBudget fits = new TextBudget(100);
        assertThat(fits.cap(sections)).isSameAs(sections);
        assertThat(fits.truncated()).isFalse();

        TextBudget cut = new TextBudget(7);
        assertThat(cut.cap(sections)).extracting(IndexedSection::content).containsExactly("12345", "67");
        assertThat(cut.truncated()).isTrue();
    }

    @Test
    void a_text_file_longer_than_the_budget_is_indexed_up_to_it_and_flagged() throws Exception {
        String line = "Le conseil municipal a délibéré sur le budget annexe.\n\n";
        byte[] data = line.repeat(2_000).getBytes(StandardCharsets.UTF_8);   // ~110 000 chars

        ParseResult limited = new TikaDocumentParser().parse(data, new Metadata(), null, null, null, null,
                null, null, null, null, null, null, new TextBudget(10_000));
        ParseResult whole = new TikaDocumentParser().parse(data, new Metadata(), null, null, null, null,
                null, null, null, null, null, null, new TextBudget(1_000_000));

        assertThat(limited.textLimited()).isTrue();
        assertThat(limited.getSections().stream().mapToInt(s -> s.content().length()).sum()).isLessThanOrEqualTo(10_000);
        assertThat(whole.textLimited()).isFalse();
    }
}
