package eu.intracherche.intracherche.web;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.impl.HttpJdkSolrClient;
import org.apache.solr.client.solrj.response.Group;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocument;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Runs the join filters built by {@link DocumentLevelMatch} against the local demo Solr, where
 * PV-CM-du-02-04-2026.pdf names "automates" on page 2 only and Saint-Michel-de-Maurienne elsewhere.
 */
@Tag("integration")
class DocumentLevelMatchIntegrationTest {

    private static final String CORE = "Index_Demo_OCR";
    private static final String QUERY = "automates saint michel de maurienne";
    private static final String PV_CM = "PV-CM-du-02-04-2026.pdf";

    @Test
    void finds_the_document_whose_pages_share_the_terms_between_them() throws Exception {
        try (SolrClient solr = new HttpJdkSolrClient.Builder("http://localhost:8983/solr").build()) {
            assumeTrue(reachable(solr), "demo Solr core " + CORE + " not available");

            SolrQuery strict = new SolrQuery(QUERY);
            strict.set("df", "content");
            strict.set("q.op", "AND");
            assumeTrue(solr.query(CORE, strict).getResults().getNumFound() == 0,
                    "demo index changed: a single page now holds every term");

            SolrQuery query = new SolrQuery(QUERY);
            query.set("df", "content");
            query.set("q.op", "OR");
            query.setRows(100);
            query.setFields("path", "page_number");
            DocumentLevelMatch.applyInclusions(query, DocumentLevelMatch.parse(QUERY), "content");
            QueryResponse response = solr.query(CORE, query);

            List<SolrDocument> pages = response.getResults();
            assertThat(pages).isNotEmpty();
            assertThat(pages).allSatisfy(p ->
                    assertThat((String) p.getFieldValue("path")).endsWith("PV-CM-du-02-04-2026.pdf"));
            assertThat(pages).anySatisfy(p -> assertThat(p.getFieldValue("page_number")).isEqualTo(2));
        }
    }

    @Test
    void grouped_result_shows_the_page_holding_the_term_the_other_pages_lack() throws Exception {
        try (SolrClient solr = new HttpJdkSolrClient.Builder("http://localhost:8983/solr").build()) {
            assumeTrue(reachable(solr), "demo Solr core " + CORE + " not available");

            // Same grouping and highlighting as SearchController's BM25 path.
            SolrQuery query = new SolrQuery(QUERY);
            query.set("df", "content");
            query.set("q.op", "OR");
            query.set("group", "true");
            query.set("group.field", "parent_id");
            query.set("group.limit", "10");
            query.setHighlight(true);
            query.addHighlightField("content");
            query.setHighlightSimplePre("<b>");
            query.setHighlightSimplePost("</b>");
            query.setHighlightSnippets(1);
            query.setHighlightFragsize(180);
            query.set("hl.bs.type", "WORD");
            DocumentLevelMatch.applyInclusions(query, DocumentLevelMatch.parse(QUERY), "content");
            QueryResponse response = solr.query(CORE, query);

            List<Group> groups = response.getGroupResponse().getValues().getFirst().getValues();
            assertThat(groups).hasSize(1);
            List<SolrDocument> pages = groups.getFirst().getResult();
            Map<String, Map<String, List<String>>> hl = response.getHighlighting();
            List<String> snippets = pages.stream().map(p -> {
                List<String> s = hl.getOrDefault((String) p.getFieldValue("id"), Map.of()).get("content");
                return s == null || s.isEmpty() ? null : s.getFirst();
            }).toList();

            List<Object> shownPages = SnippetSelector.select(snippets, SnippetSelector.queryTerms(QUERY), 3).stream()
                    .map(i -> pages.get(i).getFieldValue("page_number"))
                    .toList();

            assertThat(shownPages).hasSize(3).contains(2);
        }
    }

    @Test
    void wildcards_and_phrases_also_match_across_pages() throws Exception {
        try (SolrClient solr = new HttpJdkSolrClient.Builder("http://localhost:8983/solr").build()) {
            assumeTrue(reachable(solr), "demo Solr core " + CORE + " not available");

            assertThat(documentsMatching(solr, "autom* maurienne", "content")).containsExactly(PV_CM);
            assertThat(documentsMatching(solr, "\"saint michel\" automates", "content_exact")).containsExactly(PV_CM);
        }
    }

    @Test
    void a_negated_word_removes_the_whole_document() throws Exception {
        try (SolrClient solr = new HttpJdkSolrClient.Builder("http://localhost:8983/solr").build()) {
            assumeTrue(reachable(solr), "demo Solr core " + CORE + " not available");

            // "piscine" and "maurienne" never share a page of PV-CM, so page-level exclusion keeps it.
            assertThat(documentsMatching(solr, "piscine", "content")).contains(PV_CM);
            SolrQuery pageLevel = new SolrQuery("piscine -maurienne");
            pageLevel.set("df", "content");
            pageLevel.set("q.op", "AND");
            pageLevel.setRows(100);
            assumeTrue(solr.query(CORE, pageLevel).getResults().stream()
                    .anyMatch(p -> ((String) p.getFieldValue("path")).endsWith(PV_CM)),
                    "demo index changed: a PV-CM page now holds both words");

            assertThat(documentsMatching(solr, "piscine -maurienne", "content")).doesNotContain(PV_CM);
        }
    }

    /** File names of the documents the page-level query returns once exclusions and inclusions apply. */
    private static List<String> documentsMatching(SolrClient solr, String q, String field) throws Exception {
        DocumentLevelMatch.Units units = DocumentLevelMatch.parse(q);
        SolrQuery query = new SolrQuery(q);
        query.set("df", field);
        query.set("q.op", "OR");
        query.setRows(1000);
        query.setFields("path");
        DocumentLevelMatch.applyExclusions(query, units, field);
        if (DocumentLevelMatch.canWidenToDocument(units)) DocumentLevelMatch.applyInclusions(query, units, field);
        return solr.query(CORE, query).getResults().stream()
                .map(p -> (String) p.getFieldValue("path"))
                .map(path -> path.substring(path.lastIndexOf('\\') + 1))
                .distinct()
                .toList();
    }

    private static boolean reachable(SolrClient solr) {
        try {
            SolrQuery ping = new SolrQuery("path_lc:*pv-cm-du-02-04-2026*");
            ping.setRows(0);
            return solr.query(CORE, ping).getResults().getNumFound() > 0;
        } catch (Exception e) {
            return false;
        }
    }
}
