package eu.intracherche.intracherche.services;

import com.sun.net.httpserver.HttpServer;
import eu.intracherche.intracherche.config.OcrConfig;
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
 * With a GPU node, scanned pages are read there, and on this server's processor while the node
 * does not answer: indexing slows down rather than dropping the text of every scanned page. There
 * is no GPU node on a build machine, so both OCR services are stubs.
 */
class OcrServiceNodeFallbackTest {

    private static final byte[] PAGE = {1, 2, 3};

    private final List<HttpServer> servers = new ArrayList<>();
    private final MutableClock clock = new MutableClock();

    @AfterEach
    void stopStubs() {
        servers.forEach(s -> s.stop(0));
    }

    /** An OCR service answering every page with {@code text} and {@code status}, counting pages. */
    private String stub(String text, int status, AtomicInteger pages) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ocr", exchange -> {
            exchange.getRequestBody().readAllBytes();
            pages.incrementAndGet();
            byte[] out = text.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, out.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(out);
            }
        });
        server.createContext("/health", exchange -> {
            byte[] out = "{\"status\":\"ok\",\"version\":\"abc\",\"device\":\"cuda\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, out.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(out);
            }
        });
        server.start();
        servers.add(server);
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** A URL nothing listens on: a port opened and closed at once, so connecting is refused. */
    private String nobody() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return "http://127.0.0.1:" + socket.getLocalPort();
        }
    }

    private OcrService service(String localUrl, String nodeUrl) {
        OcrConfig config = new OcrConfig();
        config.setPythonUrl(localUrl);
        config.setWorkers(1);
        return new OcrService(RestClient.builder(), config, clock, nodeUrl);
    }

    @Test
    void pages_go_to_the_node_while_it_answers() throws IOException {
        AtomicInteger local = new AtomicInteger(), node = new AtomicInteger();
        OcrService ocr = service(stub("local", 200, local), stub("from the GPU", 200, node));

        assertThat(ocr.extractTextFromImage(PAGE)).isEqualTo("from the GPU");
        assertThat(ocr.extractTextFromImage(PAGE)).isEqualTo("from the GPU");
        assertThat(node).hasValue(2);
        assertThat(local).hasValue(0);
    }

    @Test
    void a_node_that_is_off_hands_the_page_to_this_server() throws IOException {
        AtomicInteger local = new AtomicInteger();
        OcrService ocr = service(stub("read here", 200, local), nobody());

        assertThat(ocr.extractTextFromImage(PAGE)).isEqualTo("read here");
        assertThat(local).hasValue(1);
    }

    @Test
    void a_failing_node_is_left_alone_for_a_minute_then_tried_again() throws IOException {
        AtomicInteger local = new AtomicInteger(), node = new AtomicInteger();
        OcrService ocr = service(stub("read here", 200, local), stub("", 503, node));

        ocr.extractTextFromImage(PAGE);
        clock.advance(OcrService.NODE_RETRY_AFTER.minusSeconds(1));
        ocr.extractTextFromImage(PAGE);
        assertThat(node).as("one timeout a minute, not one per page").hasValue(1);
        assertThat(local).hasValue(2);

        clock.advance(Duration.ofSeconds(1));
        ocr.extractTextFromImage(PAGE);
        assertThat(node).hasValue(2);
    }

    @Test
    void a_node_back_on_takes_the_pages_again() throws IOException {
        AtomicInteger local = new AtomicInteger(), node = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        boolean[] up = {false};
        server.createContext("/ocr", exchange -> {
            exchange.getRequestBody().readAllBytes();
            node.incrementAndGet();
            byte[] out = (up[0] ? "from the GPU" : "").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(up[0] ? 200 : 503, out.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(out);
            }
        });
        server.start();
        servers.add(server);
        OcrService ocr = service(stub("read here", 200, local),
                "http://127.0.0.1:" + server.getAddress().getPort());

        assertThat(ocr.extractTextFromImage(PAGE)).isEqualTo("read here");
        up[0] = true;
        clock.advance(OcrService.NODE_RETRY_AFTER);
        assertThat(ocr.extractTextFromImage(PAGE)).isEqualTo("from the GPU");
    }

    @Test
    void shutdown_does_not_send_the_page_to_this_server_instead() throws IOException {
        AtomicInteger local = new AtomicInteger();
        OcrService ocr = service(stub("read here", 200, local), nobody());

        Thread.currentThread().interrupt();
        try {
            assertThat(ocr.extractTextFromImage(PAGE)).isEmpty();
        } finally {
            Thread.interrupted();
        }
        assertThat(local).hasValue(0);
    }

    @Test
    void without_a_node_pages_are_read_here() throws IOException {
        AtomicInteger local = new AtomicInteger();
        OcrService ocr = service(stub("read here", 200, local), null);

        assertThat(ocr.extractTextFromImage(PAGE)).isEqualTo("read here");
        assertThat(ocr.nodeHealth()).isNull();
    }

    @Test
    void the_node_reports_where_it_reads() throws IOException {
        OcrService ocr = service(stub("", 200, new AtomicInteger()), stub("", 200, new AtomicInteger()));

        assertThat(ocr.nodeHealth()).isNotNull();
        assertThat(ocr.nodeHealth().onGpu()).isTrue();
    }

    @Test
    void a_gpu_url_sends_pages_to_the_node_and_keeps_them_in_flight() {
        OcrConfig config = new OcrConfig();
        config.setPythonUrl("http://localhost:8000");
        config.setGpuUrl("http://192.0.2.34:8004");
        config.setWorkers(1);

        assertThat(config.usesNode()).isTrue();
        assertThat(config.pagesInFlight()).isGreaterThan(config.getWorkers());

        config.setGpuUrl(" ");
        assertThat(config.usesNode()).isFalse();
        assertThat(config.pagesInFlight()).isEqualTo(1);
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-09-25T12:00:00Z");

        void advance(Duration d) { now = now.plus(d); }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
