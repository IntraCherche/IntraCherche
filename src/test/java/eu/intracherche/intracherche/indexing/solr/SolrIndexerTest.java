package eu.intracherche.intracherche.indexing.solr;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.response.UpdateResponse;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.SolrInputDocument;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SolrIndexerTest {

    private static final long[] NO_PAUSE = {0};

    private static List<SolrInputDocument> docs(int n) {
        List<SolrInputDocument> docs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            SolrInputDocument d = new SolrInputDocument();
            d.addField("id", "doc_p" + i);
            docs.add(d);
        }
        return docs;
    }

    @SuppressWarnings("unchecked")
    @Test
    void a_large_document_is_sent_in_batches_in_order() throws Exception {
        SolrClient client = mock(SolrClient.class);
        new SolrIndexer(client, "core", 500).index(docs(250), 100, NO_PAUSE);

        ArgumentCaptor<Collection<SolrInputDocument>> sent = ArgumentCaptor.forClass(Collection.class);
        verify(client, times(3)).add(eq("core"), sent.capture(), eq(500));
        assertThat(sent.getAllValues()).extracting(Collection::size).containsExactly(100, 100, 50);
        assertThat(sent.getAllValues().get(2).iterator().next().getFieldValue("id")).isEqualTo("doc_p200");
    }

    @Test
    void a_small_document_is_one_request() throws Exception {
        SolrClient client = mock(SolrClient.class);
        new SolrIndexer(client, "core", 500).index(docs(SolrIndexer.BATCH_SIZE));
        verify(client, times(1)).add(eq("core"), any(Collection.class), anyInt());
    }

    @Test
    void nothing_is_sent_for_no_documents() throws Exception {
        SolrClient client = mock(SolrClient.class);
        new SolrIndexer(client, "core", 500).index(List.of());
        verify(client, never()).add(any(String.class), any(Collection.class), anyInt());
    }

    @Test
    void a_batch_that_times_out_is_sent_again_and_the_file_goes_through() throws Exception {
        SolrClient client = mock(SolrClient.class);
        when(client.add(eq("core"), any(Collection.class), anyInt()))
                .thenThrow(new SolrServerException("Timeout occurred"))
                .thenReturn(new UpdateResponse());

        new SolrIndexer(client, "core", 500).index(docs(50), 100, NO_PAUSE);

        verify(client, times(2)).add(eq("core"), any(Collection.class), anyInt());
    }

    @Test
    void a_batch_that_keeps_failing_gives_up_after_the_retries() throws Exception {
        SolrClient client = mock(SolrClient.class);
        when(client.add(eq("core"), any(Collection.class), anyInt()))
                .thenThrow(new SolrServerException("Timeout occurred"));

        assertThatThrownBy(() -> new SolrIndexer(client, "core", 500).index(docs(50), 100, NO_PAUSE))
                .isInstanceOf(SolrServerException.class);
        verify(client, times(1 + SolrIndexer.MAX_RETRIES)).add(eq("core"), any(Collection.class), anyInt());
    }

    @Test
    void a_rejected_document_is_not_retried() throws Exception {
        SolrClient client = mock(SolrClient.class);
        when(client.add(eq("core"), any(Collection.class), anyInt()))
                .thenThrow(new SolrException(SolrException.ErrorCode.BAD_REQUEST, "bad field"));

        assertThatThrownBy(() -> new SolrIndexer(client, "core", 500).index(docs(50), 100, NO_PAUSE))
                .isInstanceOf(SolrException.class);
        verify(client, times(1)).add(eq("core"), any(Collection.class), anyInt());
    }
}
