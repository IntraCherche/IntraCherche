package eu.intracherche.intracherche.sherlock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import eu.intracherche.intracherche.config.LlmConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A model Ollama loaded while another program held the GPU stays on the processor, and Sherlock
 * answers in minutes without any error. The admin panel reads where the model sits; these are
 * the /api/ps answers the GPU box actually gave.
 */
class LlmModelPlacementTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** qwen3.5:9b loaded on the 3090: size_vram equals size. */
    private static final String ON_GPU = ps("qwen3.5:9b", 8879848064L, 8879848064L);
    /** The same model loaded while ExLlamaV3 held the card: nothing in GPU memory. */
    private static final String ON_CPU = ps("qwen3.5:9b", 8497101760L, 0);

    private HttpServer server;

    @AfterEach
    void stopStub() {
        if (server != null) server.stop(0);
    }

    @Test
    void a_model_wholly_on_the_gpu_is_fine() throws IOException {
        LlmService.ModelPlacement p = LlmService.ModelPlacement.of(MAPPER.readTree(ON_GPU), "qwen3.5:9b");

        assertThat(p.loaded()).isTrue();
        assertThat(p.gpuPercent()).isEqualTo(100);
        assertThat(p.offGpu(true)).isFalse();
    }

    @Test
    void a_model_on_the_processor_is_flagged_where_a_gpu_is_expected() throws IOException {
        LlmService.ModelPlacement p = LlmService.ModelPlacement.of(MAPPER.readTree(ON_CPU), "qwen3.5:9b");

        assertThat(p.gpuPercent()).isZero();
        assertThat(p.offGpu(true)).as("a GPU node's model on its processor").isTrue();
        assertThat(p.offGpu(false)).as("on a server without a GPU, the processor is where it belongs").isFalse();
    }

    @Test
    void a_model_split_between_card_and_processor_is_flagged_with_its_share() throws IOException {
        LlmService.ModelPlacement p = LlmService.ModelPlacement.of(
                MAPPER.readTree(ps("qwen3.5:9b", 10_000, 6_000)), "qwen3.5:9b");

        assertThat(p.gpuPercent()).isEqualTo(60);
        assertThat(p.offGpu(true)).isTrue();
        assertThat(p.offGpu(false)).as("a card on this server that holds only part of it").isTrue();
    }

    @Test
    void only_sherlocks_model_counts() throws IOException {
        // Another model on the processor says nothing about where Sherlock's will go.
        LlmService.ModelPlacement other = LlmService.ModelPlacement.of(
                MAPPER.readTree(ps("gemma4:31b", 20_000, 0)), "qwen3.5:9b");
        LlmService.ModelPlacement none = LlmService.ModelPlacement.of(MAPPER.readTree("{\"models\":[]}"), "qwen3.5:9b");
        LlmService.ModelPlacement latest = LlmService.ModelPlacement.of(
                MAPPER.readTree(ps("mistral:latest", 100, 0)), "mistral");

        assertThat(other.loaded()).isFalse();
        assertThat(other.offGpu(true)).isFalse();
        assertThat(none.loaded()).as("unloaded between questions: nothing to say").isFalse();
        assertThat(latest.loaded()).as("a tag left implicit is :latest").isTrue();
    }

    @Test
    void the_placement_is_read_from_ollama() throws IOException {
        LlmService service = serviceAnswering(ON_CPU);

        LlmService.ModelPlacement p = service.placement();

        assertThat(p).isNotNull();
        assertThat(p.loaded()).isTrue();
        assertThat(p.sizeVram()).isZero();
    }

    @Test
    void an_unreachable_ollama_gives_no_placement_rather_than_an_error() throws IOException {
        LlmService service = serviceAnswering(ON_GPU);
        server.stop(0);
        server = null;

        assertThat(service.placement()).isNull();
    }

    // ── Fixtures ─────────────────────────────────────────────────────────────

    private static String ps(String name, long size, long sizeVram) {
        return "{\"models\":[{\"name\":\"" + name + "\",\"model\":\"" + name + "\",\"size\":" + size
                + ",\"details\":{\"format\":\"gguf\"},\"size_vram\":" + sizeVram + ",\"context_length\":8192}]}";
    }

    private LlmService serviceAnswering(String psJson) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/ps", exchange -> {
            byte[] out = psJson.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, out.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(out);
            }
        });
        server.start();
        LlmConfig config = new LlmConfig();
        config.setOllamaUrl("http://127.0.0.1:" + server.getAddress().getPort());
        config.setModel("qwen3.5:9b");
        return new LlmService(config);
    }
}
