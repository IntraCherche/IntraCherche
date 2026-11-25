package eu.intracherche.intracherche.indexing.solr;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.SolrInputDocument;

import java.io.IOException;
import java.util.Collection;
import java.util.List;

public final class SolrIndexer {

    /**
     * Pages sent to Solr per request. A file is one task with a section per page, and sending
     * a 3,000-page file as one request overran the client timeout and filled a small Solr heap.
     * A page replaces its own id, so a file that fails midway is simply re-sent next cycle.
     */
    static final int BATCH_SIZE = 100;

    /** Extra attempts for a batch that fails for a reason that may pass: a timeout, a dropped connection, a 5xx. */
    static final int MAX_RETRIES = 2;
    private static final long[] RETRY_PAUSE_MS = {2_000, 10_000};

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(SolrIndexer.class);

    private final SolrClient solrClient;
    private final int commitWithinMs;
    private final String coreName;



    public SolrIndexer(SolrClient solrClient,String coreName, int commitWithinMs) {
        this.solrClient = solrClient;
        this.commitWithinMs = commitWithinMs;
        this.coreName = coreName;
    }

    public void index(Collection<SolrInputDocument> documents) throws SolrServerException, IOException {
        index(documents, BATCH_SIZE, RETRY_PAUSE_MS);
    }

    void index(Collection<SolrInputDocument> documents, int batchSize, long[] retryPauseMs)
            throws SolrServerException, IOException {
        if (documents == null || documents.isEmpty()) return;

        List<SolrInputDocument> all = List.copyOf(documents);
        for (int from = 0; from < all.size(); from += batchSize) {
            List<SolrInputDocument> batch = all.subList(from, Math.min(from + batchSize, all.size()));
            addWithRetry(batch, retryPauseMs);
        }
    }

    // Pages replace their own id, so sending a batch again after a timeout cannot duplicate it.
    private void addWithRetry(List<SolrInputDocument> batch, long[] retryPauseMs)
            throws SolrServerException, IOException {
        for (int attempt = 0; ; attempt++) {
            try {
                if (coreName != null && !coreName.isEmpty()) {
                    solrClient.add(coreName, batch, commitWithinMs);
                } else {
                    solrClient.add(batch, commitWithinMs);
                }
                return;
            } catch (SolrServerException | IOException | SolrException e) {
                if (attempt >= MAX_RETRIES || !isTransient(e)) throw e;
                long pause = retryPauseMs[Math.min(attempt, retryPauseMs.length - 1)];
                log.warn("Solr batch of {} pages for core {} failed ({}); retry {}/{} in {} ms",
                        batch.size(), coreName, e.getMessage(), attempt + 1, MAX_RETRIES, pause);
                try {
                    Thread.sleep(pause);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    /** A 4xx is a rejected document, and sending it again changes nothing. */
    private static boolean isTransient(Exception e) {
        if (e instanceof SolrException se) return se.code() >= 500;
        if (e instanceof org.apache.solr.client.solrj.impl.BaseHttpSolrClient.RemoteSolrException re) {
            return re.code() >= 500;
        }
        return true;
    }
}

