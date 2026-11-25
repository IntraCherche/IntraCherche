package eu.intracherche.intracherche.indexing.solr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.impl.HttpSolrClientBase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tells the administrator, in the log, when a failed Solr update most likely comes from Solr's
 * heap being too small for the documents being indexed. A timeout alone says nothing about why;
 * this looks at Solr's own heap figures and, if they are tight, names the cause and the
 * INSTALL.md section that explains the fix.
 */
public final class SolrHeapWatch {

    private static final Logger log = LoggerFactory.getLogger(SolrHeapWatch.class);

    /** Solr's bundled default is 512 MB; the installer now gives at least 1 GB. */
    static final long SMALL_HEAP_BYTES = 1024L * 1024 * 1024;
    static final double TIGHT_USAGE = 0.80;
    private static final long REPEAT_AFTER_MS = 10 * 60_000L;

    private static final AtomicLong lastWarnedAt = new AtomicLong(0);
    private static final ObjectMapper JSON = new ObjectMapper();

    private SolrHeapWatch() {}

    /** Called after a Solr update failed for good. Never throws, and speaks at most once per 10 minutes. */
    public static void explainIfHeapIsTight(SolrClient client, Exception failure) {
        try {
            if (!looksLikeTimeout(failure)) return;
            if (!(client instanceof HttpSolrClientBase http)) return;
            long now = System.currentTimeMillis();
            long last = lastWarnedAt.get();
            if (now - last < REPEAT_AFTER_MS) return;

            long[] heap = readHeap(http.getBaseURL());
            if (heap == null || !isTight(heap[0], heap[1])) return;
            if (!lastWarnedAt.compareAndSet(last, now)) return;
            log.error("{}", message(heap[0], heap[1]));
        } catch (Exception ignored) {
            // A diagnostic must never make a failed update worse.
        }
    }

    static boolean looksLikeTimeout(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof java.net.http.HttpTimeoutException) return true;
            String m = t.getMessage();
            if (m != null && m.contains("Timeout occurred")) return true;
        }
        return false;
    }

    static boolean isTight(long usedBytes, long maxBytes) {
        if (maxBytes <= 0) return false;
        return maxBytes < SMALL_HEAP_BYTES || (double) usedBytes / maxBytes >= TIGHT_USAGE;
    }

    static String message(long usedBytes, long maxBytes) {
        long maxMb = maxBytes / (1024 * 1024);
        long pct = Math.round(100.0 * usedBytes / maxBytes);
        return "Solr manque probablement de mémoire : son tas est de " + maxMb + " Mo et occupé à " + pct
                + " %, et une écriture dans l'index vient d'échouer par dépassement de délai. "
                + "Les documents à indexer sont trop lourds pour ce tas. Augmentez SOLR_HEAP dans "
                + "solr/bin/solr.in.sh (ou relancez l'installateur, qui le règle d'après la mémoire du "
                + "serveur), puis redémarrez IntraCherche. Voir INSTALL.md, section "
                + "« Dépannage — Solr manque de mémoire (documents très longs) ».";
    }

    /** {used, max} bytes of Solr's JVM heap, or null if Solr does not answer within 3 s. */
    private static long[] readHeap(String solrBaseUrl) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(solrBaseUrl + "/admin/metrics?group=jvm&prefix=memory.heap.&wt=json"))
                .timeout(Duration.ofSeconds(3)).GET().build();
        HttpResponse<String> response = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
                .send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) return null;
        JsonNode jvm = JSON.readTree(response.body()).path("metrics").path("solr.jvm");
        if (!jvm.has("memory.heap.used") || !jvm.has("memory.heap.max")) return null;
        return new long[]{jvm.get("memory.heap.used").asLong(), jvm.get("memory.heap.max").asLong()};
    }
}
