package eu.intracherche.intracherche.indexing.worker;

import eu.intracherche.intracherche.indexing.model.IndexTask;
import eu.intracherche.intracherche.indexing.parser.IndexedSection;
import org.apache.solr.common.SolrInputDocument;
import org.apache.tika.metadata.Metadata;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Several sections of a Word document start on the same estimated page. Keyed on the page, they
 * would overwrite one another in Solr: they keep their position in the id, the page goes to
 * page_number.
 */
class EstimatedPageIdsTest {

    @Test
    void sections_sharing_an_estimated_page_keep_their_own_solr_id() {
        List<IndexedSection> sections = List.of(
                IndexedSection.onEstimatedPage("Page 1", "début", 1),
                IndexedSection.onEstimatedPage("Page 1", "suite", 1),
                IndexedSection.onEstimatedPage("Page 2", "fin", 2));

        List<SolrInputDocument> docs = new IndexWorker("w", null, 0).convertToSolrDocuments(task(sections));

        assertThat(docs).extracting(d -> d.getFieldValue("id")).containsExactly("doc1_p1", "doc1_p2", "doc1_p3");
        assertThat(docs).extracting(d -> d.getFieldValue("page_number")).containsExactly(1, 1, 2);
    }

    @Test
    void a_pdf_page_keeps_its_page_as_id() {
        // Page 2 went to OCR: page 3's id must stay _p3, which the OCR result for page 2 does not reuse.
        List<IndexedSection> sections = List.of(
                new IndexedSection("Page 1", "texte", false, 1),
                new IndexedSection("Page 3", "texte", false, 3));

        List<SolrInputDocument> docs = new IndexWorker("w", null, 0).convertToSolrDocuments(task(sections));

        assertThat(docs).extracting(d -> d.getFieldValue("id")).containsExactly("doc1_p1", "doc1_p3");
    }

    private static IndexTask task(List<IndexedSection> sections) {
        return new IndexTask("doc1", "Index_Test", "CR.docx",
                sections.stream().map(IndexTask.SectionContent::fromIndexedSection).toList(),
                new Metadata(), Set.of("S-1"), Set.of(), 0L, null, null, null, false);
    }
}
