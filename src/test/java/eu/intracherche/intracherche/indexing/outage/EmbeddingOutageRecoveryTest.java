package eu.intracherche.intracherche.indexing.outage;

import com.sun.net.httpserver.HttpServer;
import eu.intracherche.intracherche.config.EmbeddingConfig;
import eu.intracherche.intracherche.indexing.enrichment.VectorEnricher;
import eu.intracherche.intracherche.indexing.outage.ServiceOutageMonitor.Dependency;
import eu.intracherche.intracherche.indexing.parser.IndexedSection;
import eu.intracherche.intracherche.indexing.parser.ParseResult;
import eu.intracherche.intracherche.indexing.pipeline.PipelineContext;
import eu.intracherche.intracherche.services.EmbeddingService;
import org.apache.tika.metadata.Metadata;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The whole chain over real HTTP: the embedding service is down when a file is enriched, the
 * failed call and /health mark it down, the enricher waits, and once the service answers the
 * file gets its vectors — nothing indexed without them, nothing failed.
 */
class EmbeddingOutageRecoveryTest {

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    /** A free port nothing listens on yet: connecting is refused until the stub starts there. */
    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    private void startEmbeddingStub(int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/embed-batch", exchange -> {
            exchange.getRequestBody().readAllBytes();
            respond(exchange, "{\"vectors\":[[0.5,0.25]]}");
        });
        server.createContext("/health", exchange -> respond(exchange, "{\"status\":\"ok\"}"));
        server.start();
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String body) throws IOException {
        byte[] out = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, out.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(out);
        }
    }

    @Test
    void a_file_enriched_while_the_service_is_down_gets_its_vectors_once_it_is_back() throws Exception {
        int port = freePort();
        EmbeddingConfig config = new EmbeddingConfig();
        config.setPythonUrl("http://127.0.0.1:" + port);
        EmbeddingService embeddingService = new EmbeddingService(RestClient.builder(), config);
        ServiceOutageMonitor monitor = new ServiceOutageMonitor(
                Map.of(Dependency.EMBEDDING, embeddingService::isHealthy),
                Clock.systemUTC(), Duration.ofMillis(200));
        VectorEnricher enricher = new VectorEnricher(embeddingService, monitor, () -> false);

        PipelineContext ctx = new PipelineContext(Path.of("doc.pdf"), null);
        ctx.setParseResult(new ParseResult(List.of(new IndexedSection("Page 1", "Compte rendu du conseil")),
                new Metadata(), "application/pdf"));

        CompletableFuture<Void> enriching = CompletableFuture.runAsync(() -> {
            try {
                enricher.enrich(ctx);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });

        // The enricher found the service down and waits for it, without failing the file.
        long deadline = System.currentTimeMillis() + 10_000;
        while (!monitor.isDown(Dependency.EMBEDDING) && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertThat(monitor.isDown(Dependency.EMBEDDING)).isTrue();
        assertThat(enriching).isNotDone();

        startEmbeddingStub(port);

        enriching.get(10, TimeUnit.SECONDS);
        assertThat(monitor.outages()).isEmpty();
        assertThat(ctx.getSectionVectors()).hasSize(1);
        assertThat(ctx.getSectionVectors().getFirst()).containsExactly(0.5f, 0.25f);
    }

    /**
     * Ctrl-C during indexing (Sept. 2026): the embedding service had died with the app, and an OCR
     * worker interrupted by the shutdown checked /health before waiting. HttpClient.send cleared
     * the interrupt, the worker then waited for a service that would never come back, and the JVM
     * hung for the 120 s stop timeout while the indexer flooded the console with I/O errors.
     */
    @Test
    void a_worker_interrupted_before_checking_health_stops_waiting_at_once() throws Exception {
        EmbeddingConfig config = new EmbeddingConfig();
        config.setPythonUrl("http://127.0.0.1:" + freePort());
        EmbeddingService embeddingService = new EmbeddingService(RestClient.builder(), config);
        ServiceOutageMonitor monitor = new ServiceOutageMonitor(
                Map.of(Dependency.EMBEDDING, embeddingService::isHealthy),
                Clock.systemUTC(), Duration.ofMillis(200));

        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            Thread.currentThread().interrupt();
            try {
                if (monitor.confirmOutage(Dependency.EMBEDDING)) {
                    monitor.awaitRecovery(Dependency.EMBEDDING, () -> false);
                }
            } catch (Throwable t) {
                thrown.set(t);
            }
        });
        worker.start();
        worker.join(5_000);

        assertThat(worker.isAlive()).as("worker still waiting for the service").isFalse();
        assertThatThrownBy(() -> { throw thrown.get(); }).isInstanceOf(ServiceUnavailableException.class);
    }
}
