package eu.intracherche.intracherche.services;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.intracherche.intracherche.config.OcrConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.http.MediaType;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Sends a page image to the OCR service and returns its text.
 *
 * <p>With a GPU node ({@code ocr.gpuUrl}), pages go to the node's OCR first and to
 * this server's own OCR service when the node does not answer — asleep, restarting, unplugged —
 * so indexing slows down instead of losing the text of scanned pages. After a failure the node is
 * left alone for {@link #NODE_RETRY_AFTER}, so that a machine that is off costs one connection
 * timeout a minute rather than one per page.
 */
@Service
public class OcrService {

    static final Duration NODE_RETRY_AFTER = Duration.ofSeconds(60);
    /** A node that is off never refuses the connection: the attempt just times out. */
    private static final Duration NODE_CONNECT_TIMEOUT = Duration.ofSeconds(5);

    private final RestClient ocrClient;
    /** Both null without a GPU node. */
    private final String nodeUrl;
    private final RestClient nodeClient;
    private final OcrConfig config;
    private final Clock clock;
    /** While in the future, pages skip the node. */
    private volatile Instant nodeSkippedUntil = Instant.MIN;
    /** Whether the last page sent to the node was read there: logs each change once. */
    private final AtomicBoolean nodeAnswering = new AtomicBoolean(true);
    /**
     * Pages read by this server's service at once, one per worker process. The pool sends
     * several pages at a time to a GPU node; falling back, they would otherwise queue behind a
     * single local worker and time out there.
     */
    private volatile Semaphore localSlots;
    private static final Logger log = LoggerFactory.getLogger(OcrService.class);
    private static final ObjectMapper HEALTH_MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);


    @Autowired
    public OcrService(RestClient.Builder builder, OcrConfig config) {
        this(builder, config, Clock.systemUTC(), config.usesNode() ? config.getGpuUrl() : null);
    }

    /** {@code nodeUrl}: the GPU node's OCR, or null without one. */
    OcrService(RestClient.Builder builder, OcrConfig config, Clock clock, String nodeUrl) {
        this.config = config;
        this.clock = clock;
        this.ocrClient = client(builder, config.getPythonUrl(), null);
        this.nodeUrl = nodeUrl;
        this.nodeClient = nodeUrl == null ? null : client(builder, nodeUrl, NODE_CONNECT_TIMEOUT);
    }

    private RestClient client(RestClient.Builder builder, String baseUrl, Duration connectTimeout) {
        // Force a dedicated 1.1 client with no protocol negotiation
        java.net.http.HttpClient.Builder http = java.net.http.HttpClient.newBuilder()
                .version(java.net.http.HttpClient.Version.HTTP_1_1);
        if (connectTimeout != null) http.connectTimeout(connectTimeout);

        // Configurable timeout so a slow OCR doesn't block Java threads forever
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http.build());
        factory.setReadTimeout(Duration.ofSeconds(config.getReadTimeoutSeconds()));

        return builder.clone()
                .requestFactory(factory)
                .baseUrl(baseUrl)
                .build();
    }

    /**
     * Pings this server's OCR service — the one IntraCherche starts — with a short timeout.
     * Returns true if the service responds with HTTP 200, false otherwise.
     * Logs the service version on success.
     */
    public boolean isHealthy() {
        return health(config.getPythonUrl()) != null;
    }

    /** The GPU node's OCR as it reports itself, or null when there is no node or it does not answer. */
    public Health nodeHealth() {
        return nodeUrl == null ? null : health(nodeUrl);
    }

    private Health health(String baseUrl) {
        try {
            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                    .version(java.net.http.HttpClient.Version.HTTP_1_1)
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(baseUrl + "/health"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            java.net.http.HttpResponse<String> response =
                    client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return null;
            try {
                Health health = HEALTH_MAPPER.readValue(response.body(), Health.class);
                log.debug("OCR service at {} reachable — version: {}, device: {}", baseUrl, health.version(), health.device());
                return health;
            } catch (Exception e) {
                log.debug("OCR service at {} reachable (version unknown)", baseUrl);
                return new Health("ok", null, null);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // see EmbeddingService.isHealthy
            return null;
        } catch (Exception e) {
            log.debug("OCR health check of {} failed: {}", baseUrl, e.getMessage());
            return null;
        }
    }

    /** What an OCR service's /health says. {@code device} is "cuda" or "cpu"; null before 3.3. */
    public record Health(String status, String version, String device) {
        public boolean onGpu() { return "cuda".equals(device); }
    }

    /**
     * The text of a page image. Empty for a page with no text, or when the thread is interrupted.
     *
     * @throws org.springframework.web.client.RestClientException when this server's service
     *         fails too (the GPU node, if any, has failed or is being left alone)
     */
    public String extractTextFromImage(byte[] imageBytes) {
        if (imageBytes == null || imageBytes.length == 0) {
            log.warn("OCR skipped: Provided image bytes are empty.");
            return "";
        }

        if (nodeClient != null && !clock.instant().isBefore(nodeSkippedUntil)) {
            try {
                String text = post(nodeClient, imageBytes);
                if (!nodeAnswering.getAndSet(true)) {
                    log.info("The GPU node's OCR at {} answers again: pages are read there.", nodeUrl);
                }
                return text;
            } catch (Exception e) {
                // Shutdown interrupts the workers: that is not the node failing, and there is
                // no one left to read the page for.
                if (Thread.currentThread().isInterrupted()) return "";
                nodeSkippedUntil = clock.instant().plus(NODE_RETRY_AFTER);
                if (nodeAnswering.getAndSet(false)) {
                    log.warn("The GPU node's OCR at {} did not answer ({}): pages are read on this server's "
                            + "processor, much more slowly, and the node is tried again every {} s.",
                            nodeUrl, rootCause(e), NODE_RETRY_AFTER.toSeconds());
                }
            }
        }

        Semaphore slots = localSlots();
        try {
            slots.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        }
        // A failure here is thrown, not read as an empty page: a document whose page was lost
        // that way was marked indexed without its text. The caller tells an outage apart.
        try {
            return post(ocrClient, imageBytes);
        } finally {
            slots.release();
        }
    }

    private static String post(RestClient client, byte[] imageBytes) {
        return client.post()
                .uri("/ocr")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(imageBytes)
                .retrieve()
                .body(String.class);
    }

    /**
     * What went wrong, one level under Spring's "I/O error on POST request" wrapper:
     * "ConnectException" (refused), "HttpConnectTimeoutException: HTTP connect timed out" (off).
     */
    static String rootCause(Throwable e) {
        Throwable cause = e.getCause() != null ? e.getCause() : e;
        String message = cause.getMessage();
        return cause.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
    }

    /** Created on first use: the worker count is only final once ResourceValidator has run. */
    private Semaphore localSlots() {
        Semaphore slots = localSlots;
        if (slots == null) {
            synchronized (this) {
                if (localSlots == null) localSlots = new Semaphore(Math.max(1, config.getWorkers()), true);
                slots = localSlots;
            }
        }
        return slots;
    }

}