package eu.intracherche.intracherche.services;

import com.sun.net.httpserver.HttpServer;
import eu.intracherche.intracherche.config.NerConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for {@link NerService#getGazetteer()} against a stub NER service on a random port.
 */
class NerServiceGazetteerTest {

    private static final String BODY = """
            {"countries": ["Allemagne", "France"],
             "regions": ["Nouvelle-Aquitaine", "Provence-Alpes-Côte d'Azur"],
             "departments": [{"name": "Gironde", "region": "Nouvelle-Aquitaine"}]}
            """;

    private final AtomicInteger hits = new AtomicInteger();
    private volatile int status = 200;
    private HttpServer server;

    @BeforeEach
    void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/gazetteer", exchange -> {
            hits.incrementAndGet();
            byte[] body = BODY.getBytes(StandardCharsets.UTF_8);
            if (status != 200) {
                exchange.sendResponseHeaders(status, -1);
                exchange.close();
                return;
            }
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    @AfterEach
    void stopStub() {
        server.stop(0);
    }

    private NerService newService() {
        NerConfig config = new NerConfig();
        config.setPythonUrl("http://127.0.0.1:" + server.getAddress().getPort());
        return new NerService(RestClient.builder(), config);
    }

    @Test
    void parsesTheGazetteerAndCachesIt() {
        NerService service = newService();

        NerService.Gazetteer first = service.getGazetteer().orElseThrow();
        NerService.Gazetteer second = service.getGazetteer().orElseThrow();

        assertThat(first.countries()).containsExactly("Allemagne", "France");
        assertThat(first.regions()).contains("Provence-Alpes-Côte d'Azur");
        assertThat(first.departments())
                .containsExactly(new NerService.Department("Gironde", "Nouvelle-Aquitaine"));
        assertThat(second).isSameAs(first);
        assertThat(hits).hasValue(1);
    }

    @Test
    void doesNotCacheAFailure() {
        NerService service = newService();

        status = 503;
        assertThat(service.getGazetteer()).isEmpty();

        status = 200;
        assertThat(service.getGazetteer()).isPresent();
        assertThat(hits).hasValue(2);
    }
}
