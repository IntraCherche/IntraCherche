package eu.intracherche.intracherche.sherlock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import eu.intracherche.intracherche.config.LlmConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The llm.* settings INSTALL.md tells an admin to change, checked against what actually reaches
 * Ollama: a stand-in Ollama on a local port records every request.
 */
class LlmSettingsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<SherlockResult> ONE_PASSAGE = List.of(new SherlockResult(
            "doc1", "/rapport.pdf", "rapport.pdf", "Le rapport est signé par Jean Martin.",
            "Le rapport est signé par Jean Martin.", 1));

    private HttpServer ollama;
    private final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    /** How long the stand-in waits before answering. */
    private volatile long stallMillis;

    @BeforeEach
    void startOllama() throws Exception {
        ollama = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ollama.setExecutor(Executors.newCachedThreadPool());
        ollama.createContext("/api/chat", exchange -> {
            requests.add(MAPPER.readTree(exchange.getRequestBody()));
            try {
                Thread.sleep(stallMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] body = "{\"message\":{\"content\":\"Jean Martin [DOCUMENT 1 - rapport.pdf]\"},\"done\":true}\n"
                    .getBytes(StandardCharsets.UTF_8);
            try (OutputStream out = exchange.getResponseBody()) {
                exchange.sendResponseHeaders(200, body.length);
                out.write(body);
            } catch (Exception ignored) {
                // the client gave up (read timeout): nobody left to answer
            }
        });
        ollama.start();
    }

    @AfterEach
    void stopOllama() {
        ollama.stop(0);
    }

    @Test
    void the_configured_model_and_context_window_are_sent() {
        LlmConfig config = config();
        config.setModel("qwen3.5:4b");
        config.setNumCtx(12288);

        new LlmService(config).synthesizeAnswer("Qui a signé le rapport ?", ONE_PASSAGE);

        assertThat(requests).hasSize(1);
        assertThat(requests.getFirst().path("model").asText()).isEqualTo("qwen3.5:4b");
        assertThat(requests.getFirst().path("options").path("num_ctx").asInt()).isEqualTo(12288);
    }

    /** numCtx: 0 = "s'en remettre à l'environnement d'Ollama": no num_ctx at all. */
    @Test
    void a_zero_context_window_sends_none() {
        LlmConfig config = config();
        config.setNumCtx(0);

        new LlmService(config).synthesizeAnswer("Qui a signé le rapport ?", ONE_PASSAGE);

        assertThat(requests).hasSize(1);
        assertThat(requests.getFirst().path("options").has("num_ctx")).isFalse();
    }

    /** llm.readTimeoutSeconds: past it the question fails instead of waiting on Ollama. */
    @Test
    void a_generation_past_the_read_timeout_is_abandoned() {
        LlmConfig config = config();
        config.setReadTimeoutSeconds(1);
        stallMillis = 5_000;
        LlmService service = new LlmService(config);

        long start = System.nanoTime();
        assertThatThrownBy(() -> service.synthesizeAnswer("Qui a signé le rapport ?", ONE_PASSAGE))
                .hasMessageContaining("read timeout")
                .hasMessageContaining("llm.readTimeoutSeconds");
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMillis).isLessThan(4_000);
    }

    /** llm.enabled: false — not one request reaches Ollama. */
    @Test
    void a_disabled_llm_never_calls_ollama() {
        LlmConfig config = config();
        config.setEnabled(false);
        LlmService service = new LlmService(config);

        service.expandQuery("Qui a signé le rapport ?");
        service.synthesizeAnswer("Qui a signé le rapport ?", ONE_PASSAGE);

        assertThat(requests).isEmpty();
    }

    private LlmConfig config() {
        LlmConfig config = new LlmConfig();
        config.setOllamaUrl("http://127.0.0.1:" + ollama.getAddress().getPort());
        return config;
    }
}
