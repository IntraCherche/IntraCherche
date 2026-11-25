package eu.intracherche.intracherche.services;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import eu.intracherche.intracherche.config.EmbeddingConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * With several uvicorn workers sharing one listening socket, a kept-alive connection stays with the
 * worker that accepted it. The pooled connections of a long indexing run all ended up on the same
 * worker, and the others sat idle. Every call therefore opens a connection of its own.
 */
class EmbeddingServiceOwnConnectionTest {

    private HttpServer server;
    /** The client port of every request, one per TCP connection. */
    private final List<Integer> clientPorts = new CopyOnWriteArrayList<>();
    private EmbeddingService service;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/embed", exchange -> {
            clientPorts.add(exchange.getRemoteAddress().getPort());
            respond(exchange, "{\"vector\":[0.5,0.6]}");
        });
        server.createContext("/embed-batch", exchange -> {
            clientPorts.add(exchange.getRemoteAddress().getPort());
            respond(exchange, "{\"vectors\":[[0.1,0.2]]}");
        });
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();

        EmbeddingConfig config = new EmbeddingConfig();
        config.setPythonUrl("http://127.0.0.1:" + server.getAddress().getPort());
        config.setWorkers(2);
        service = new EmbeddingService(RestClient.builder(), config);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void every_call_uses_a_connection_of_its_own() {
        for (int i = 0; i < 4; i++) service.embed("texte " + i, false);
        service.embedBatch(List.of("a", "b"), false);
        service.embedBatch(List.of("c", "d"), false);

        assertThat(clientPorts).hasSize(6);
        assertThat(clientPorts).doesNotHaveDuplicates();
    }

    private static void respond(HttpExchange exchange, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }
}
