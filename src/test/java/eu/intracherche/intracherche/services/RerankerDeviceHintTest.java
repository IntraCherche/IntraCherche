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
 * The reranker tells the log what it is running on, and says so when a GPU is being used as
 * though it were a processor. There is no GPU on a build machine, so the branch that matters is
 * tested here rather than discovered on a customer's server.
 */
class RerankerDeviceHintTest {

    private HttpServer server;

    @AfterEach
    void stopStub() {
        if (server != null) server.stop(0);
    }

    private RerankerService serviceReporting(String healthJson) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/health", exchange -> {
            byte[] out = healthJson.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, out.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(out);
            }
        });
        server.start();
        RerankerConfig config = new RerankerConfig();
        config.setPythonUrl("http://127.0.0.1:" + server.getAddress().getPort());
        return new RerankerService(RestClient.builder(), config);
    }

    @Test
    void health_is_read_even_though_the_service_reports_more_than_is_asked_for() throws IOException {
        // The real payload: unknown fields must not make the whole read fail.
        RerankerService service = serviceReporting("{\"status\":\"ok\",\"model\":\"/opt/m\","
                + "\"version\":\"abc1234\",\"max_passages\":40,\"batch_size\":8,\"threads\":4,"
                + "\"device\":\"cuda:0\"}");

        RerankerService.RerankerHealth health = service.health();

        assertThat(health).isNotNull();
        assertThat(health.device()).isEqualTo("cuda:0");
        assertThat(health.batch_size()).isEqualTo(8);
    }

    @Test
    void an_unreachable_service_reports_nothing_rather_than_failing() throws IOException {
        RerankerService service = serviceReporting("{\"device\":\"cpu\"}");
        server.stop(0);
        server = null;

        assertThat(service.health()).isNull();
    }

    @Test
    void a_gpu_left_at_the_processor_setting_is_pointed_out() {
        String advice = RerankerService.gpuAdvice("cuda:0", 40, 32);

        assertThat(advice).isNotNull()
                .contains("reranker.candidates is 40")
                .contains("to 0 (auto) or 100")
                .contains("Accélération GPU");
    }

    @Test
    void the_pool_follows_the_device_the_reranker_reports() throws IOException {
        assertThat(serviceReporting("{\"device\":\"cuda:0\"}").candidates()).isEqualTo(100);
        stopStub();
        assertThat(serviceReporting("{\"device\":\"cpu\"}").candidates()).isEqualTo(40);
    }

    @Test
    void a_reranker_that_does_not_answer_gets_the_processor_pool() throws IOException {
        RerankerService service = serviceReporting("{\"device\":\"cuda:0\"}");
        server.stop(0);
        server = null;

        assertThat(service.onGpu()).isFalse();
        assertThat(service.candidates()).as("the pool that cannot time out").isEqualTo(40);
    }

    @Test
    void nothing_is_said_when_there_is_nothing_to_gain() {
        assertThat(RerankerService.gpuAdvice("cpu", 40, 8)).as("a processor is not a GPU").isNull();
        assertThat(RerankerService.gpuAdvice("cuda:0", 120, 32)).as("already sized for the card").isNull();
        assertThat(RerankerService.gpuAdvice("cuda:0", 100, 32)).as("exactly at the figure").isNull();
        assertThat(RerankerService.gpuAdvice(null, 40, 8)).as("device unknown").isNull();
    }
}
