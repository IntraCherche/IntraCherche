package eu.intracherche.intracherche.web;

import org.apache.solr.client.solrj.SolrQuery;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentLevelMatchTest {

    @Test
    void plain_words_become_escaped_units() {
        DocumentLevelMatch.Units units = DocumentLevelMatch.parse("  automates saint-michel  maurienne ");

        assertThat(units.included()).containsExactly("automates", "saint\\-michel", "maurienne");
        assertThat(units.excluded()).isEmpty();
    }

    @Test
    void wildcards_are_kept_and_phrases_stay_whole() {
        DocumentLevelMatch.Units units = DocumentLevelMatch.parse("autom?tes* \"saint michel\" piscine");

        assertThat(units.included()).containsExactly("autom?tes*", "\"saint michel\"", "piscine");
    }

    @Test
    void negated_words_and_phrases_are_exclusions() {
        DocumentLevelMatch.Units units = DocumentLevelMatch.parse("piscine -confidentiel -\"projet x\"");

        assertThat(units.included()).containsExactly("piscine");
        assertThat(units.excluded()).containsExactly("confidentiel", "\"projet x\"");
        assertThat(DocumentLevelMatch.canWidenToDocument(units)).isFalse();
    }

    @Test
    void not_is_the_same_exclusion_as_a_minus() {
        DocumentLevelMatch.Units units = DocumentLevelMatch.parse("eau piscine NOT mer NOT \"mer du nord\"");

        assertThat(units.included()).containsExactly("eau", "piscine");
        assertThat(units.excluded()).containsExactly("mer", "\"mer du nord\"");
        assertThat(DocumentLevelMatch.parse("eau NOT")).isNull();
        assertThat(DocumentLevelMatch.parse("eau NOT NOT mer")).isNull();
        assertThat(DocumentLevelMatch.parse("eau NOT -mer")).isNull();
    }

    @Test
    void other_syntax_is_not_parsed() {
        assertThat(DocumentLevelMatch.parse("path:cr automates")).isNull();
        assertThat(DocumentLevelMatch.parse("(automates OR regulateurs) piscine")).isNull();
        assertThat(DocumentLevelMatch.parse("automates AND piscine")).isNull();
        assertThat(DocumentLevelMatch.parse("+automates piscine")).isNull();
        assertThat(DocumentLevelMatch.parse("automates~ piscine")).isNull();
        assertThat(DocumentLevelMatch.parse("automates --piscine")).isNull();
        assertThat(DocumentLevelMatch.parse("\"saint michel piscine")).isNull();
        assertThat(DocumentLevelMatch.parse("\" \" piscine")).isNull();
        assertThat(DocumentLevelMatch.parse("-confidentiel")).isNull();
        assertThat(DocumentLevelMatch.parse("a b c d e -f -g -h -i")).isNull();
        assertThat(DocumentLevelMatch.parse(null)).isNull();
    }

    @Test
    void a_single_positive_unit_has_nothing_to_widen() {
        assertThat(DocumentLevelMatch.canWidenToDocument(DocumentLevelMatch.parse("automates"))).isFalse();
        assertThat(DocumentLevelMatch.canWidenToDocument(DocumentLevelMatch.parse("automates piscine"))).isTrue();
    }

    @Test
    void inclusions_are_removable_and_exclusions_are_negated_joins() {
        SolrQuery query = new SolrQuery("x");
        query.addFilterQuery("index_source:(A OR B)");
        DocumentLevelMatch.Units units = DocumentLevelMatch.parse("saint-michel automates -confidentiel");

        DocumentLevelMatch.applyExclusions(query, units, "content");
        DocumentLevelMatch.applyInclusions(query, units, "content");

        assertThat(query.getFilterQueries()).containsExactly("index_source:(A OR B)",
                "-{!join from=parent_id to=parent_id v=$docnot0}",
                "{!join from=parent_id to=parent_id v=$docterm0}",
                "{!join from=parent_id to=parent_id v=$docterm1}");
        assertThat(query.get("docterm0")).isEqualTo("{!lucene q.op=AND df=content}saint\\-michel");
        assertThat(query.get("docnot0")).isEqualTo("{!lucene q.op=AND df=content}confidentiel");

        DocumentLevelMatch.removeInclusions(query, units);

        assertThat(query.getFilterQueries()).containsExactly("index_source:(A OR B)",
                "-{!join from=parent_id to=parent_id v=$docnot0}");
        assertThat(query.get("docterm0")).isNull();
    }
}
