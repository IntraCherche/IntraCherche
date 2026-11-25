package eu.intracherche.intracherche.services;

import eu.intracherche.intracherche.config.EmbeddingConfig;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@Service
public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);

    // Transient I/O failures (connection dropped/reset by an overloaded uvicorn worker) are
    // retried a few times with a short linear backoff before the chunk is given up on.
    private static final int MAX_ATTEMPTS = 3;
    private static final long RETRY_BASE_DELAY_MS = 200;

    private final RestClient client;
    private final EmbeddingConfig config;

    // Bounds the number of in-flight HTTP calls to the embedding service across ALL callers
    // (every concurrently-indexed document plus query-time embedding). Each uvicorn worker
    // processes one embed call at a time, so letting more connections than workers pile up
    // only overflows the server's accept backlog and gets connections dropped. Query-side
    // calls go ahead of indexing ones (see EmbeddingGate). Sized lazily from the worker count,
    // which ResourceValidator resolves before any indexing happens.
    private volatile EmbeddingGate gate;

    public EmbeddingService(RestClient.Builder builder, EmbeddingConfig config) {
        this.config = config;
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(config.getReadTimeoutSeconds()));
        this.client = builder
                .requestFactory(factory)
                .baseUrl(config.getPythonUrl())
                .build();
    }

    /**
     * Runs one call to the embedding service on a connection of its own, closed afterwards.
     *
     * <p>With several workers (embedding.workers) the uvicorn processes share one listening socket
     * and whichever wins the accept gets the connection. A kept-alive connection stays with that
     * worker for good, so the pooled connections of a long indexing run all ended up on the same
     * one: the other worker sat at 1% CPU and the second worker's memory bought nothing. A new
     * connection per call lets each call be accepted afresh, by a worker that is free to take it.
     */
    private <T> T onOwnConnection(java.util.function.Function<RestClient, T> call) {
        try (HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
            JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
            factory.setReadTimeout(Duration.ofSeconds(config.getReadTimeoutSeconds()));
            return call.apply(client.mutate().requestFactory(factory).build());
        }
    }

    @PostConstruct
    void logAvailability() {
        if (!config.isEnabled()) {
            log.info("Embedding service disabled — hybrid search will use BM25 only");
            return;
        }
        if (!isHealthy()) {
            log.warn("Embedding service not reachable at {} — vector enrichment will be skipped until it is started",
                    config.getPythonUrl());
        }
    }

    public boolean isEnabled() {
        return config.isEnabled();
    }

    public boolean isHealthy() {
        try {
            HttpClient httpClient = HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(config.getPythonUrl() + "/health"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                log.debug("Embedding service reachable at {}", config.getPythonUrl());
                return true;
            }
            return false;
        } catch (InterruptedException e) {
            // HttpClient.send clears the flag when it throws: restore it, or a worker stopped at
            // shutdown would wait out the outage and hold the JVM for the full stop timeout.
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            log.debug("Embedding health check failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Embeds a single piece of text. Returns null on failure or when disabled.
     *
     * @param text    the text to embed
     * @param isQuery true for query-side embedding, false for document passage
     */
    public float[] embed(String text, boolean isQuery) {
        if (!config.isEnabled() || text == null || text.isBlank()) return null;
        try {
            EmbedResponse response = withGateAndRetry("embed", isQuery, () -> onOwnConnection(c -> c.post()
                    .uri("/embed")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("text", text, "is_query", isQuery))
                    .retrieve()
                    .body(EmbedResponse.class)));
            if (response == null || response.vector() == null || response.vector().isEmpty()) return null;
            return toFloatArray(response.vector());
        } catch (Exception e) {
            log.warn("Embedding service call failed ({}): vector enrichment skipped for this chunk", e.getMessage());
            return null;
        }
    }

    /** Outcome of a query-time embedding: the vector, or why there is none. */
    public enum QueryEmbeddingStatus { OK, BUSY, FAILED }

    public record QueryEmbedding(float[] vector, QueryEmbeddingStatus status) {
        static QueryEmbedding busy()   { return new QueryEmbedding(null, QueryEmbeddingStatus.BUSY); }
        static QueryEmbedding failed() { return new QueryEmbedding(null, QueryEmbeddingStatus.FAILED); }
    }

    /**
     * Embeds a search query for an interactive user, who must not wait behind the indexer.
     *
     * <p>Indexing and query embedding share one gate. The query goes ahead of every queued
     * indexing call, so it normally waits only for the calls already running. Should those
     * run long (a saturated CPU), it waits at most {@code embedding.queryMaxWaitMillis} and
     * returns {@link QueryEmbeddingStatus#BUSY}; the caller then searches without vectors and
     * tells the user why.</p>
     */
    public QueryEmbedding embedQuery(String text) {
        if (!config.isEnabled() || text == null || text.isBlank()) return QueryEmbedding.failed();
        EmbeddingGate g = gate();
        try {
            if (!g.acquirePriority(config.getQueryMaxWaitMillis(), TimeUnit.MILLISECONDS)) {
                log.info("Embedding service saturated: no slot within {} ms, query searched without vectors",
                        config.getQueryMaxWaitMillis());
                return QueryEmbedding.busy();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return QueryEmbedding.failed();
        }
        try {
            EmbedResponse response = callWithRetry("embed-query", () -> onOwnConnection(c -> c.post()
                    .uri("/embed")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("text", text, "is_query", true))
                    .retrieve()
                    .body(EmbedResponse.class)));
            if (response == null || response.vector() == null || response.vector().isEmpty()) {
                return QueryEmbedding.failed();
            }
            return new QueryEmbedding(toFloatArray(response.vector()), QueryEmbeddingStatus.OK);
        } catch (Exception e) {
            log.warn("Query embedding failed ({}): query searched without vectors", e.getMessage());
            return QueryEmbedding.failed();
        } finally {
            g.release();
        }
    }

    /**
     * Embeds a batch of texts in a single model inference call. Returns an empty list on failure —
     * callers should fall back to per-section {@link #embed} calls in that case.
     * The returned list preserves input positions: null entries correspond to blank input texts.
     */
    public List<float[]> embedBatch(List<String> texts, boolean isQuery) {
        if (!config.isEnabled() || texts == null || texts.isEmpty()) return Collections.emptyList();
        try {
            EmbedBatchResponse response = withGateAndRetry("embed-batch", isQuery, () -> onOwnConnection(c -> c.post()
                    .uri("/embed-batch")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("texts", texts, "is_query", isQuery))
                    .retrieve()
                    .body(EmbedBatchResponse.class)));
            if (response == null || response.vectors() == null) return Collections.emptyList();
            return response.vectors().stream()
                    .map(v -> (v == null || v.isEmpty()) ? null : toFloatArray(v))
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("Batch embedding failed ({}): falling back to per-section embedding", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Runs an embedding-service call while holding one permit of the global concurrency gate,
     * retrying transient I/O failures (connection dropped/reset under load) with a short linear
     * backoff. HTTP error responses are not retried — they propagate to the caller's handler.
     * Query-side calls (a Sherlock question) wait ahead of passage calls (indexing).
     */
    private <T> T withGateAndRetry(String op, boolean priority, Supplier<T> call) {
        EmbeddingGate g = gate();
        try {
            if (priority) g.acquirePriority(); else g.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("interrupted while waiting for an embedding slot", e);
        }
        try {
            return callWithRetry(op, call);
        } finally {
            g.release();
        }
    }

    /** Runs a call, retrying transient I/O failures. The caller must already hold a gate permit. */
    private <T> T callWithRetry(String op, Supplier<T> call) {
        ResourceAccessException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return call.get();
            } catch (ResourceAccessException e) {
                last = e;
                if (attempt < MAX_ATTEMPTS) {
                    long delay = RETRY_BASE_DELAY_MS * attempt;
                    log.debug("{} attempt {}/{} failed ({}); retrying in {} ms",
                            op, attempt, MAX_ATTEMPTS, e.getMessage(), delay);
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw last;
                    }
                }
            }
        }
        throw last;
    }

    /** Lazily builds the concurrency gate sized to the (by-now resolved) worker count. */
    private EmbeddingGate gate() {
        EmbeddingGate g = gate;
        if (g == null) {
            synchronized (this) {
                g = gate;
                if (g == null) {
                    int permits = Math.max(1, config.getWorkers());
                    gate = g = new EmbeddingGate(permits);
                    log.debug("Embedding concurrency gate sized to {} permit(s)", permits);
                }
            }
        }
        return g;
    }

    private static float[] toFloatArray(List<Double> list) {
        float[] arr = new float[list.size()];
        for (int i = 0; i < list.size(); i++) {
            arr[i] = list.get(i).floatValue();
        }
        return arr;
    }

    private record EmbedResponse(List<Double> vector) {}

    private record EmbedBatchResponse(List<List<Double>> vectors) {}
}
