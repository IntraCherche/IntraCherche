package eu.intracherche.intracherche.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import eu.intracherche.intracherche.config.RerankerConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for passage truncation in {@link RerankerService#rerank} against a stub reranker.
 */
class RerankerServiceTruncationTest {

    private final AtomicReference<JsonNode> received = new AtomicReference<>();
    private HttpServer server;

    @BeforeEach
    void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/rerank", exchange -> {
            JsonNode body = new ObjectMapper().readTree(exchange.getRequestBody());
            received.set(body);
            StringBuilder scores = new StringBuilder("{\"scores\":[");
            for (int i = 0; i < body.get("passages").size(); i++) scores.append(i == 0 ? "" : ",").append(i);
            byte[] out = scores.append("]}").toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, out.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(out);
            }
        });
        server.start();
    }

    @AfterEach
    void stopStub() {
        server.stop(0);
    }

    private RerankerService newService(int maxPassageChars) {
        RerankerConfig config = new RerankerConfig();
        config.setPythonUrl("http://127.0.0.1:" + server.getAddress().getPort());
        config.setMaxPassageChars(maxPassageChars);
        return new RerankerService(RestClient.builder(), config);
    }

    @Test
    void long_passages_are_cut_before_scoring_and_scores_keep_input_order() {
        List<Double> scores = newService(10).rerank("q", List.of("x".repeat(25), "court"));

        assertThat(scores).containsExactly(0.0, 1.0);
        assertThat(received.get().get("passages").get(0).asText()).hasSize(10);
        assertThat(received.get().get("passages").get(1).asText()).isEqualTo("court");
    }

    @Test
    void zero_disables_truncation() {
        newService(0).rerank("q", List.of("x".repeat(25)));

        assertThat(received.get().get("passages").get(0).asText()).hasSize(25);
    }
}
