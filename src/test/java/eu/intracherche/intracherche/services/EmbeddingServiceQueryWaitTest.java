package eu.intracherche.intracherche.services;

import com.sun.net.httpserver.HttpServer;
import eu.intracherche.intracherche.config.EmbeddingConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A search must not queue behind the indexer on the shared embedding gate: during a large
 * indexing run that wait lasted minutes and the browser gave up, showing no results at all.
 */
class EmbeddingServiceQueryWaitTest {

    private static final long QUERY_MAX_WAIT_MS = 300;

    private HttpServer server;
    private final CountDownLatch batchStarted = new CountDownLatch(1);
    private final CountDownLatch releaseBatch = new CountDownLatch(1);
    /** Paths in the order the embedding service received them. */
    private final List<String> arrivals = new CopyOnWriteArrayList<>();
    private EmbeddingService service;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // An indexing batch that holds its slot until the test lets it go
        server.createContext("/embed-batch", exchange -> {
            arrivals.add("/embed-batch");
            batchStarted.countDown();
            try {
                releaseBatch.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            respond(exchange, "{\"vectors\":[[0.1,0.2]]}");
        });
        server.createContext("/embed", exchange -> {
            arrivals.add("/embed");
            respond(exchange, "{\"vector\":[0.5,0.6]}");
        });
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();

        service = serviceWaitingAtMost(QUERY_MAX_WAIT_MS);
    }

    /** A one-slot service, so a single indexing batch in flight saturates it. */
    private EmbeddingService serviceWaitingAtMost(long queryMaxWaitMs) {
        EmbeddingConfig config = new EmbeddingConfig();
        config.setPythonUrl("http://127.0.0.1:" + server.getAddress().getPort());
        config.setWorkers(1);
        config.setQueryMaxWaitMillis(queryMaxWaitMs);
        return new EmbeddingService(RestClient.builder(), config);
    }

    @AfterEach
    void tearDown() {
        releaseBatch.countDown();
        server.stop(0);
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String json) throws java.io.IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    @Test
    void query_gets_its_vector_when_the_service_is_free() {
        EmbeddingService.QueryEmbedding result = service.embedQuery("remplacement");

        assertThat(result.status()).isEqualTo(EmbeddingService.QueryEmbeddingStatus.OK);
        assertThat(result.vector()).containsExactly(0.5f, 0.6f);
    }

    @Test
    void query_gives_up_quickly_while_indexing_holds_every_slot() throws Exception {
        CompletableFuture<List<float[]>> indexing =
                CompletableFuture.supplyAsync(() -> service.embedBatch(List.of("page"), false));
        assertThat(batchStarted.await(5, TimeUnit.SECONDS)).isTrue();

        long start = System.nanoTime();
        EmbeddingService.QueryEmbedding result = service.embedQuery("remplacement");
        long waitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertThat(result.status()).isEqualTo(EmbeddingService.QueryEmbeddingStatus.BUSY);
        assertThat(result.vector()).isNull();
        assertThat(waitedMs).isBetween(QUERY_MAX_WAIT_MS - 50, QUERY_MAX_WAIT_MS + 2000);

        // The indexer is unaffected, and once it is done the slot is free for searches again
        releaseBatch.countDown();
        assertThat(indexing.get(5, TimeUnit.SECONDS)).hasSize(1);
        assertThat(service.embedQuery("remplacement").status())
                .isEqualTo(EmbeddingService.QueryEmbeddingStatus.OK);
    }

    @Test
    void query_is_served_before_indexing_batches_queued_ahead_of_it() throws Exception {
        service = serviceWaitingAtMost(5000);
        CompletableFuture<List<float[]>> inFlight =
                CompletableFuture.supplyAsync(() -> service.embedBatch(List.of("page 1"), false));
        assertThat(batchStarted.await(5, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<List<float[]>> queued =
                CompletableFuture.supplyAsync(() -> service.embedBatch(List.of("page 2"), false));
        Thread.sleep(300); // let the second batch reach the gate and queue there

        CompletableFuture<EmbeddingService.QueryEmbedding> search =
                CompletableFuture.supplyAsync(() -> service.embedQuery("remplacement"));
        Thread.sleep(300);
        releaseBatch.countDown();

        assertThat(search.get(5, TimeUnit.SECONDS).status()).isEqualTo(EmbeddingService.QueryEmbeddingStatus.OK);
        inFlight.get(5, TimeUnit.SECONDS);
        queued.get(5, TimeUnit.SECONDS);
        assertThat(arrivals).containsExactly("/embed-batch", "/embed", "/embed-batch");
    }

    @Test
    void query_reports_failure_when_the_service_is_down() {
        server.stop(0);

        EmbeddingService.QueryEmbedding result = service.embedQuery("remplacement");

        assertThat(result.status()).isEqualTo(EmbeddingService.QueryEmbeddingStatus.FAILED);
    }
}
