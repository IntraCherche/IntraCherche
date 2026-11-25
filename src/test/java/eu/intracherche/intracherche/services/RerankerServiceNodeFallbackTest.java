package eu.intracherche.intracherche.services;

import com.sun.net.httpserver.HttpServer;
import eu.intracherche.intracherche.config.RerankerConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * With a GPU node, questions are scored there, and on this server's processor while the node does
 * not answer — its owner stopped the service to use the card, or the machine is asleep. There is
 * no GPU node on a build machine, so both rerankers are stubs.
 */
class RerankerServiceNodeFallbackTest {

    private static final List<String> PASSAGES = List.of("un", "deux");

    private final List<HttpServer> servers = new ArrayList<>();
    private final MutableClock clock = new MutableClock();

    @AfterEach
    void stopStubs() {
        servers.forEach(s -> s.stop(0));
    }

    /** A reranker on {@code device} scoring every passage {@code score}, counting questions. */
    private String stub(String device, double score, int status, AtomicInteger questions) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/rerank", exchange -> {
            exchange.getRequestBody().readAllBytes();
            questions.incrementAndGet();
            respond(exchange, status, "{\"scores\":[" + score + "," + score + "]}");
        });
        server.createContext("/health", exchange ->
                respond(exchange, 200, "{\"device\":\"" + device + "\",\"batch_size\":8,\"max_passages\":100}"));
        server.start();
        servers.add(server);
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws IOException {
        byte[] out = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, out.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(out);
        }
    }

    /** A URL nothing listens on: a port opened and closed at once, so connecting is refused. */
    private String nobody() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return "http://127.0.0.1:" + socket.getLocalPort();
        }
    }

    private RerankerService service(String localUrl, String nodeUrl) {
        RerankerConfig config = new RerankerConfig();
        config.setPythonUrl(localUrl);
        config.setGpuUrl(nodeUrl);
        return new RerankerService(RestClient.builder(), config, clock);
    }

    @Test
    void questions_are_scored_on_the_node_while_it_answers() throws IOException {
        AtomicInteger local = new AtomicInteger(), node = new AtomicInteger();
        RerankerService reranker = service(stub("cpu", 0.1, 200, local), stub("cuda:0", 0.9, 200, node));

        assertThat(reranker.onGpu()).isTrue();
        assertThat(reranker.candidates()).as("a GPU's pool").isEqualTo(100);
        assertThat(reranker.rerank("q", PASSAGES)).containsExactly(0.9, 0.9);
        assertThat(node).hasValue(1);
        assertThat(local).hasValue(0);
    }

    @Test
    void a_stopped_node_hands_the_question_to_this_server_with_a_processors_pool() throws IOException {
        AtomicInteger local = new AtomicInteger();
        RerankerService reranker = service(stub("cpu", 0.1, 200, local), nobody());

        assertThat(reranker.onGpu()).as("sized before retrieval, for what will score it").isFalse();
        assertThat(reranker.candidates()).isEqualTo(40);
        assertThat(reranker.rerank("q", PASSAGES)).containsExactly(0.1, 0.1);
        assertThat(local).hasValue(1);
    }

    @Test
    void a_node_stopped_between_sizing_and_scoring_still_gets_the_question_scored() throws IOException {
        AtomicInteger local = new AtomicInteger(), node = new AtomicInteger();
        HttpServer nodeServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        nodeServer.createContext("/health", exchange ->
                respond(exchange, 200, "{\"device\":\"cuda:0\",\"batch_size\":32,\"max_passages\":100}"));
        nodeServer.start();
        String nodeUrl = "http://127.0.0.1:" + nodeServer.getAddress().getPort();
        RerankerService reranker = service(stub("cpu", 0.1, 200, local), nodeUrl);

        assertThat(reranker.onGpu()).isTrue();
        nodeServer.stop(0);

        assertThat(reranker.rerank("q", PASSAGES)).containsExactly(0.1, 0.1);
        assertThat(local).hasValue(1);
        assertThat(node).hasValue(0);
    }

    @Test
    void a_stopped_node_is_left_alone_for_a_minute_then_takes_the_questions_back() throws IOException {
        AtomicInteger local = new AtomicInteger(), node = new AtomicInteger();
        HttpServer server = HttpServer.create();
        int port;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = socket.getLocalPort();
        }
        String nodeUrl = "http://127.0.0.1:" + port;
        RerankerService reranker = service(stub("cpu", 0.1, 200, local), nodeUrl);

        reranker.rerank("q", PASSAGES);
        assertThat(local).hasValue(1);

        // Started again by its owner, within the minute: not asked yet.
        server.bind(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/rerank", exchange -> {
            exchange.getRequestBody().readAllBytes();
            node.incrementAndGet();
            respond(exchange, 200, "{\"scores\":[0.9,0.9]}");
        });
        server.createContext("/health", exchange ->
                respond(exchange, 200, "{\"device\":\"cuda:0\",\"batch_size\":32,\"max_passages\":100}"));
        server.start();
        servers.add(server);
        clock.advance(NodeFallback.RETRY_AFTER.minusSeconds(1));
        assertThat(reranker.onGpu()).isFalse();
        reranker.rerank("q", PASSAGES);
        assertThat(node).as("one attempt a minute, not one per question").hasValue(0);
        assertThat(local).hasValue(2);

        clock.advance(Duration.ofSeconds(1));
        assertThat(reranker.onGpu()).isTrue();
        assertThat(reranker.rerank("q", PASSAGES)).containsExactly(0.9, 0.9);
        assertThat(node).hasValue(1);
    }

    @Test
    void a_node_refusing_the_question_is_not_hidden_by_this_server() throws IOException {
        AtomicInteger local = new AtomicInteger(), node = new AtomicInteger();
        RerankerService reranker = service(stub("cpu", 0.1, 200, local), stub("cuda:0", 0.9, 422, node));

        assertThat(reranker.rerank("q", PASSAGES)).as("retrieval order, the setting to fix stays visible").isNull();
        assertThat(node).hasValue(1);
        assertThat(local).hasValue(0);
    }

    @Test
    void without_a_node_questions_are_scored_at_python_url() throws IOException {
        AtomicInteger local = new AtomicInteger();
        RerankerService reranker = service(stub("cpu", 0.1, 200, local), "");

        assertThat(reranker.rerank("q", PASSAGES)).containsExactly(0.1, 0.1);
        assertThat(reranker.isNodeHealthy()).isFalse();
        assertThat(reranker.nodeDeployment()).isNull();
    }

    @Test
    void each_row_of_the_admin_panel_reports_its_own_reranker() throws IOException {
        RerankerService reranker = service(stub("cpu", 0.1, 200, new AtomicInteger()),
                stub("cuda:0", 0.9, 200, new AtomicInteger()));

        assertThat(reranker.isNodeHealthy()).isTrue();
        assertThat(reranker.nodeDeployment().device()).isEqualTo("cuda:0");
        assertThat(reranker.nodeDeployment().candidates()).isEqualTo(100);
        assertThat(reranker.localDeployment().device()).isEqualTo("cpu");
        assertThat(reranker.localDeployment().candidates()).isEqualTo(40);
    }

    @Test
    void a_blank_gpu_url_means_no_node() {
        RerankerConfig config = new RerankerConfig();
        config.setGpuUrl(" http://192.0.2.34:8003 ");
        assertThat(config.usesNode()).isTrue();
        assertThat(config.getGpuUrl()).isEqualTo("http://192.0.2.34:8003");

        config.setGpuUrl(" ");
        assertThat(config.usesNode()).isFalse();
        config.setGpuUrl(null);
        assertThat(config.usesNode()).isFalse();
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-09-29T12:00:00Z");

        void advance(Duration d) { now = now.plus(d); }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
