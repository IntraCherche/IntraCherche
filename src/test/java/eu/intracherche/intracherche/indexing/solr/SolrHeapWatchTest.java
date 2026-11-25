package eu.intracherche.intracherche.indexing.solr;

import org.apache.solr.client.solrj.SolrServerException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SolrHeapWatchTest {

    private static final long MB = 1024L * 1024;

    @Test
    void a_small_heap_is_tight_even_when_idle() {
        assertTrue(SolrHeapWatch.isTight(100 * MB, 512 * MB));
    }

    @Test
    void a_large_heap_is_tight_only_when_nearly_full() {
        assertFalse(SolrHeapWatch.isTight(400 * MB, 2048 * MB));
        assertTrue(SolrHeapWatch.isTight(1800 * MB, 2048 * MB));
        assertFalse(SolrHeapWatch.isTight(0, 0));
    }

    @Test
    void only_a_timeout_triggers_the_hint() {
        assertTrue(SolrHeapWatch.looksLikeTimeout(new SolrServerException("Timeout occurred while waiting response from server at: x")));
        assertFalse(SolrHeapWatch.looksLikeTimeout(new SolrServerException("Bad Request")));
    }

    @Test
    void the_message_names_the_cause_the_fix_and_the_documentation_section() {
        String m = SolrHeapWatch.message(470 * MB, 512 * MB);
        assertTrue(m.contains("512 Mo") && m.contains("92 %"), m);
        assertTrue(m.contains("SOLR_HEAP") && m.contains("INSTALL.md") && m.contains("Solr manque de mémoire"), m);
    }
}
