package eu.intracherche.intracherche.services;

import com.sun.net.httpserver.HttpServer;
import eu.intracherche.intracherche.config.RerankerConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two ways a GPU install fails without an error of its own — the reranker refusing every
 * question, or running on the processor while the admin believes it is on the GPU — must be
 * reported.
 */
class RerankerDeploymentTest {

    private HttpServer server;

    @AfterEach
    void stopStub() {
        if (server != null) server.stop(0);
    }

    @Test
    void a_reranker_that_refuses_the_configured_pool_is_reported() {
        RerankerService.Deployment deployment = RerankerService.deployment(
                new RerankerService.RerankerHealth("cuda:0", 32, 50), true, 100);

        assertThat(deployment.problems()).containsExactly(RerankerService.Problem.TOO_FEW_PASSAGES);
        assertThat(deployment.messages()).singleElement().asString()
                .contains("at most 50 passages")
                .contains("reranker.candidates is 100")
                .contains("RERANKER_MAX_PASSAGES");
    }

    @Test
    void a_reranker_on_another_machine_running_on_its_processor_is_reported() {
        RerankerService.Deployment deployment = RerankerService.deployment(
                new RerankerService.RerankerHealth("cpu", 32, 150), true, 100);

        assertThat(deployment.problems()).containsExactly(RerankerService.Problem.NOT_ON_GPU);
        assertThat(deployment.messages()).singleElement().asString()
                .contains("on its processor (cpu)")
                .contains("set the GPU node up again");
    }

    @Test
    void a_healthy_deployment_reports_nothing() {
        assertThat(RerankerService.deployment(
                new RerankerService.RerankerHealth("cuda:0", 32, 150), true, 100).problems()).isEmpty();
        assertThat(RerankerService.deployment(
                new RerankerService.RerankerHealth("cpu", 8, 40), false, 40).problems())
                .as("the processor is right for a reranker on this server").isEmpty();
        assertThat(RerankerService.deployment(null, true, 100).problems())
                .as("unreadable: reported as down elsewhere, not as misconfigured").isEmpty();
    }

    @Test
    void the_deployment_is_read_from_the_running_service() throws IOException {
        RerankerService service = serviceAt(stub("{\"device\":\"cpu\",\"batch_size\":8,\"max_passages\":40}"));

        RerankerService.Deployment deployment = service.deployment();

        assertThat(deployment.device()).isEqualTo("cpu");
        assertThat(deployment.maxPassages()).isEqualTo(40);
        assertThat(deployment.candidates()).isEqualTo(40);
        assertThat(deployment.problems()).as("a CPU reranker on this server capped at 40, asked 40").isEmpty();
    }

    // ── Fixtures ─────────────────────────────────────────────────────────────

    /** A reranker answering /health with the given JSON. */
    private int stub(String healthJson) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/health", exchange -> reply(exchange, healthJson));
        server.start();
        return server.getAddress().getPort();
    }

    private static void reply(com.sun.net.httpserver.HttpExchange exchange, String json) throws IOException {
        byte[] out = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, out.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(out);
        }
    }

    private static RerankerService serviceAt(int port) {
        RerankerConfig config = new RerankerConfig();
        config.setPythonUrl("http://127.0.0.1:" + port);
        return new RerankerService(RestClient.builder(), config);
    }
}
