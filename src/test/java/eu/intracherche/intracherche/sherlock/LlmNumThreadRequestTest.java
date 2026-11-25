package eu.intracherche.intracherche.sherlock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.intracherche.intracherche.config.LlmConfig;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LlmNumThreadRequestTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode options(LlmService service, boolean onThisServer) throws Exception {
        return MAPPER.readTree(service.chatRequestBody("q", false, "m", onThisServer)).path("options");
    }

    @Test
    void this_servers_ollama_gets_the_thread_count_next_to_the_context_window() throws Exception {
        LlmConfig config = new LlmConfig();
        config.setNumThread(6);
        JsonNode options = options(new LlmService(config), true);
        assertThat(options.path("num_thread").asInt()).isEqualTo(6);
        assertThat(options.path("num_ctx").asInt()).isEqualTo(8192);
    }

    @Test
    void a_gpu_node_never_gets_a_thread_count() throws Exception {
        LlmConfig config = new LlmConfig();
        config.setNumThread(6);
        JsonNode options = options(new LlmService(config), false);
        assertThat(options.has("num_thread")).isFalse();
        assertThat(options.path("num_ctx").asInt()).isEqualTo(8192);
    }

    @Test
    void an_ollama_on_another_host_never_gets_one_either() throws Exception {
        LlmConfig config = new LlmConfig();
        config.setNumThread(6);
        config.setOllamaUrl("http://192.0.2.34:11434");
        assertThat(options(new LlmService(config), true).has("num_thread")).isFalse();
    }

    @Test
    void zero_with_a_pinned_window_sends_the_window_alone_on_a_small_machine() throws Exception {
        LlmConfig config = new LlmConfig();            // numThread 0: automatic
        JsonNode options = options(new LlmService(config), true);
        if (Runtime.getRuntime().availableProcessors() < LlmConfig.AUTO_THREADS_MIN_CORES) {
            assertThat(options.has("num_thread")).isFalse();
        }
        assertThat(options.path("num_ctx").asInt()).isEqualTo(8192);
    }
}
