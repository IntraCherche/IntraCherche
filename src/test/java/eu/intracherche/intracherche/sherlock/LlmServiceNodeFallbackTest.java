package eu.intracherche.intracherche.sherlock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import eu.intracherche.intracherche.config.LlmConfig;
import eu.intracherche.intracherche.services.NodeFallback;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * With a GPU node, Sherlock answers there with the node's model, and with this server's Ollama and
 * model while the node does not answer — its owner stopped Ollama to use the card, or the machine
 * is asleep. Both Ollamas are stand-ins that record the model each question asked for.
 */
class LlmServiceNodeFallbackTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<SherlockResult> ONE_PASSAGE = List.of(new SherlockResult(
            "doc1", "/rapport.pdf", "rapport.pdf", "Le rapport est signé par Jean Martin.",
            "Le rapport est signé par Jean Martin.", 1));
    private static final String DONE = "{\"message\":{\"content\":\"Jean Martin\"},\"done\":true}\n";

    private final List<HttpServer> servers = new ArrayList<>();
    private final MutableClock clock = new MutableClock();

    @AfterEach
    void stopStubs() {
        servers.forEach(s -> s.stop(0));
    }

    /** An Ollama streaming {@code body} after {@code stallMillis}, recording the models asked for. */
    private String ollama(String body, long stallMillis, List<String> models) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/api/chat", exchange -> {
            models.add(MAPPER.readTree(exchange.getRequestBody()).path("model").asText());
            try {
                Thread.sleep(stallMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = exchange.getResponseBody()) {
                exchange.sendResponseHeaders(200, out.length);
                os.write(out);
            } catch (Exception ignored) {
                // the client gave up
            }
        });
        server.createContext("/api/tags", exchange -> {
            byte[] out = "{\"models\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, out.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(out);
            }
        });
        server.start();
        servers.add(server);
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private String nobody() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return "http://127.0.0.1:" + socket.getLocalPort();
        }
    }

    private LlmService service(String localUrl, String nodeUrl) {
        LlmConfig config = new LlmConfig();
        config.setOllamaUrl(localUrl);
        config.setModel("qwen3.5:4b");
        config.setGpuUrl(nodeUrl);
        config.setGpuModel("qwen3.5:9b");
        config.setReadTimeoutSeconds(2);
        return new LlmService(config, clock);
    }

    @Test
    void questions_are_answered_on_the_node_with_its_model() throws IOException {
        List<String> local = new CopyOnWriteArrayList<>(), node = new CopyOnWriteArrayList<>();
        LlmService llm = service(ollama(DONE, 0, local), ollama(DONE, 0, node));

        assertThat(llm.synthesizeAnswer("Qui a signé ?", ONE_PASSAGE)).isEqualTo("Jean Martin");
        assertThat(node).containsExactly("qwen3.5:9b");
        assertThat(local).isEmpty();
    }

    @Test
    void a_stopped_node_hands_the_question_to_this_server_and_its_model() throws IOException {
        List<String> local = new CopyOnWriteArrayList<>();
        LlmService llm = service(ollama(DONE, 0, local), nobody());

        assertThat(llm.synthesizeAnswer("Qui a signé ?", ONE_PASSAGE)).isEqualTo("Jean Martin");
        assertThat(local).containsExactly("qwen3.5:4b");
    }

    @Test
    void a_node_stopped_in_the_middle_of_an_answer_has_it_answered_again_here() throws IOException {
        List<String> local = new CopyOnWriteArrayList<>(), node = new CopyOnWriteArrayList<>();
        String halfAnswer = "{\"message\":{\"content\":\"Jean\"},\"done\":false}\n";
        LlmService llm = service(ollama(DONE, 0, local), ollama(halfAnswer, 0, node));

        assertThat(llm.synthesizeAnswer("Qui a signé ?", ONE_PASSAGE))
                .as("the whole answer, not the half the node sent").isEqualTo("Jean Martin");
        assertThat(node).hasSize(1);
        assertThat(local).hasSize(1);
    }

    @Test
    void a_slow_node_is_not_retried_on_a_slower_processor() throws IOException {
        List<String> local = new CopyOnWriteArrayList<>(), node = new CopyOnWriteArrayList<>();
        LlmService llm = service(ollama(DONE, 0, local), ollama(DONE, 5_000, node));

        assertThatThrownBy(() -> llm.synthesizeAnswer("Qui a signé ?", ONE_PASSAGE))
                .hasMessageContaining("read timeout");
        assertThat(local).isEmpty();
    }

    @Test
    void a_stopped_node_is_left_alone_for_a_minute_then_tried_again() throws IOException {
        List<String> local = new CopyOnWriteArrayList<>(), node = new CopyOnWriteArrayList<>();
        String cutOff = "{\"message\":{\"content\":\"Jean\"},\"done\":false}\n";
        LlmService llm = service(ollama(DONE, 0, local), ollama(cutOff, 0, node));

        llm.synthesizeAnswer("Qui a signé ?", ONE_PASSAGE);
        clock.advance(NodeFallback.RETRY_AFTER.minusSeconds(1));
        llm.synthesizeAnswer("Qui a signé ?", ONE_PASSAGE);
        assertThat(node).as("one attempt a minute, not one per question").hasSize(1);
        assertThat(local).hasSize(2);

        clock.advance(Duration.ofSeconds(1));
        llm.synthesizeAnswer("Qui a signé ?", ONE_PASSAGE);
        assertThat(node).hasSize(2);
    }

    @Test
    void sherlock_takes_questions_while_either_ollama_answers() throws IOException {
        LlmService nodeOnly = service(nobody(), ollama(DONE, 0, new CopyOnWriteArrayList<>()));
        assertThat(nodeOnly.isHealthy()).isTrue();
        assertThat(nodeOnly.isLocalHealthy()).isFalse();
        assertThat(nodeOnly.isNodeHealthy()).isTrue();

        LlmService localOnly = service(ollama(DONE, 0, new CopyOnWriteArrayList<>()), nobody());
        assertThat(localOnly.isHealthy()).isTrue();
        assertThat(localOnly.isNodeHealthy()).isFalse();

        assertThat(service(nobody(), nobody()).isHealthy()).isFalse();
    }

    @Test
    void the_node_answers_with_the_main_model_when_it_names_none() {
        LlmConfig config = new LlmConfig();
        config.setModel("qwen3.5:9b");
        assertThat(config.usesNode()).isFalse();
        assertThat(config.resolvedGpuModel()).isEqualTo("qwen3.5:9b");

        config.setGpuUrl("http://192.0.2.34:11434");
        config.setGpuModel("qwen3.5:27b");
        assertThat(config.usesNode()).isTrue();
        assertThat(config.resolvedGpuModel()).isEqualTo("qwen3.5:27b");
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-09-29T12:00:00Z");

        void advance(Duration d) { now = now.plus(d); }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
