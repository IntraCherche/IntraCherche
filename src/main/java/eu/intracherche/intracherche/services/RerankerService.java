package eu.intracherche.intracherche.services;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import eu.intracherche.intracherche.config.Hosts;
import eu.intracherche.intracherche.config.RerankerConfig;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class RerankerService {

    private static final Logger log = LoggerFactory.getLogger(RerankerService.class);

    /** Candidates a GPU serves comfortably, and the figure the hint points an admin at. */
    private static final int GPU_CANDIDATES_HINT = 100;

    /** How long to wait for a reranker that does not answer at all, such as a sleeping GPU node. */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    private final RerankerConfig config;
    /** The reranker at reranker.pythonUrl: this server's, or the only one there is. */
    private final Endpoint local;
    /** The GPU node's reranker (reranker.gpuUrl), tried first; both null without one. */
    private final Endpoint node;
    private final NodeFallback fallback;
    private final AtomicBoolean deviceHintLogged = new AtomicBoolean();
    /** Set while calls fail, so an outage is logged once rather than on every question. */
    private final AtomicBoolean unreachable = new AtomicBoolean();

    /** One reranker: where it answers, and what it last said about its device. */
    private static final class Endpoint {
        final String url;
        final RestClient client;
        /** Short timeouts: /health is read by the admin panel, which must not hang on a dead node. */
        final RestClient healthClient;
        /**
         * Whether it last reported a GPU, null until it has answered. Read from the service
         * rather than declared in the configuration, so the pool follows the machine that
         * actually scores: a GPU node whose PyTorch fell back to its processor gets a processor's pool.
         */
        volatile Boolean onGpu;

        Endpoint(RestClient.Builder builder, String url, int readTimeoutSeconds) {
            this.url = url;
            HttpClient httpClient = HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(CONNECT_TIMEOUT)
                    .build();
            JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
            factory.setReadTimeout(Duration.ofSeconds(readTimeoutSeconds));
            JdkClientHttpRequestFactory healthFactory = new JdkClientHttpRequestFactory(httpClient);
            healthFactory.setReadTimeout(Duration.ofSeconds(5));
            this.healthClient = builder.clone().requestFactory(healthFactory).baseUrl(url).build();
            this.client = builder.clone().requestFactory(factory).baseUrl(url).build();
        }
    }

    @Autowired
    public RerankerService(RestClient.Builder builder, RerankerConfig config) {
        this(builder, config, Clock.systemUTC());
    }

    RerankerService(RestClient.Builder builder, RerankerConfig config, Clock clock) {
        this.config = config;
        this.local = new Endpoint(builder, config.getPythonUrl(), config.getReadTimeoutSeconds());
        this.node = config.usesNode() ? new Endpoint(builder, config.getGpuUrl(), config.getReadTimeoutSeconds()) : null;
        this.fallback = node == null ? null : new NodeFallback("reranker", node.url, clock);
    }

    /** The reranker the next question goes to: the node unless it is being left alone. */
    private Endpoint target() {
        return node != null && fallback.worthTrying() ? node : local;
    }

    @PostConstruct
    void logAvailability() {
        if (!config.isUsed()) {
            log.info("Reranker service disabled (reranker.enabled or llm.enabled is false)");
            return;
        }
        if (node != null) {
            if (isNodeHealthy()) {
                log.info("Questions are scored on the GPU node's reranker at {}, and at {} while it does not answer",
                        node.url, local.url);
            } else {
                log.warn("The GPU node's reranker does not answer at {}: questions are scored at {} until it does",
                        node.url, local.url);
            }
        }
        if (!isHealthy()) {
            log.warn("Reranker service not reachable at {} — snippet ranking will be skipped until it is started",
                    config.getPythonUrl());
        }
    }

    public boolean isEnabled() {
        return config.isUsed();
    }

    /** Whether the reranker at reranker.pythonUrl answers. */
    public boolean isHealthy() {
        return answers(config.getPythonUrl());
    }

    /** Whether the GPU node's reranker answers; false without one. */
    public boolean isNodeHealthy() {
        return node != null && answers(node.url);
    }

    private static boolean answers(String baseUrl) {
        try {
            HttpClient httpClient = HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/health"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                log.debug("Reranker service reachable at {}", baseUrl);
                return true;
            }
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            log.debug("Reranker health check of {} failed: {}", baseUrl, e.getMessage());
            return false;
        }
    }

    /**
     * Scores each passage against the query. Returns scores in the same order as passages,
     * or null if the service is disabled or unreachable.
     */
    public List<Double> rerank(String query, List<String> passages) {
        if (!config.isUsed() || query == null || passages == null || passages.isEmpty()) return null;
        logDeviceHintOnce();
        // Scoring cost grows with passage length, and past the read timeout every question falls
        // back to retrieval order. Sherlock sends page windows of up to 2500 chars; the model reads
        // 512 tokens at most, so characters beyond reranker.maxPassageChars would only cost time.
        int max = config.getMaxPassageChars();
        List<String> truncated = max <= 0 ? passages : passages.stream()
                .map(p -> p != null && p.length() > max ? p.substring(0, max) : p)
                .toList();
        if (node != null && fallback.worthTrying()) {
            try {
                RerankerResponse response = post(node, query, truncated);
                fallback.answered();
                return scores(response, passages.size());
            } catch (ResourceAccessException e) {
                // Stopped by its owner, asleep, rebooting: this server scores the question instead.
                // A shutdown interrupts the worker: that is not the node failing.
                if (Thread.currentThread().isInterrupted()) return null;
                node.onGpu = null; // it may come back restarted, on another device
                fallback.failed(e);
            } catch (Exception e) {
                // It answered with an error — a refused pool: a setting to fix on the node, which
                // this server's reranker would only hide.
                fallback.answered();
                log.warn("The GPU node's reranker refused the question ({}): using retrieval order", e.getMessage());
                return null;
            }
        }
        try {
            RerankerResponse response = post(local, query, truncated);
            if (unreachable.getAndSet(false)) {
                log.info("Reranker service reachable again at {}", local.url);
            }
            return scores(response, passages.size());
        } catch (ResourceAccessException e) {
            // Nobody answering — a GPU node asleep or rebooting fails every question until it is
            // back: say so once, not once per question.
            local.onGpu = null; // what answers next may be another machine, or the same one restarted
            if (!unreachable.getAndSet(true)) {
                log.warn("Reranker service not reachable at {} ({}): using retrieval order until it answers again",
                        local.url, e.getMessage());
            } else {
                log.debug("Reranker service still not reachable ({}): using retrieval order", e.getMessage());
            }
            return null;
        } catch (Exception e) {
            // It answered with an error — a refused pool: a setting to fix, which
            // must stay visible on every question. It did answer, so the next silence is news.
            unreachable.set(false);
            log.warn("Reranker service call failed ({}): using retrieval order", e.getMessage());
            return null;
        }
    }

    private static RerankerResponse post(Endpoint endpoint, String query, List<String> passages) {
        return endpoint.client.post()
                .uri("/rerank")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("query", query, "passages", passages))
                .retrieve()
                .body(RerankerResponse.class);
    }

    /** The scores, or null when there are none for every passage. */
    private List<Double> scores(RerankerResponse response, int passages) {
        if (response == null || response.scores() == null) return null;
        if (response.scores().size() != passages) {
            // Scoring only some of them would rank a truncated pool against a full one.
            log.warn("Reranker scored {} of {} passages; using retrieval order. Check that the "
                    + "service's RERANKER_MAX_PASSAGES matches reranker.candidates ({}).",
                    response.scores().size(), passages, candidates());
            return null;
        }
        return response.scores();
    }

    /**
     * Says once, in the log, where the reranker runs, and when it runs on a GPU with a
     * reranker.candidates set by hand for a processor.
     *
     * <p>A GPU scores in about a second what a processor takes a minute over, so the candidate
     * pool — where Sherlock's recall is decided — can be two or three times deeper for free.
     * The auto value (0) takes that from the device; a number written for a processor does not,
     * and nothing else reveals it: the setting works, it is simply far below what the machine
     * could do.
     *
     * <p>Sent from the first scoring rather than from startup, because at startup the service
     * is still loading its model and would not answer.
     */
    private void logDeviceHintOnce() {
        if (!deviceHintLogged.compareAndSet(false, true)) return;
        RerankerHealth health = health();
        if (health == null || health.device() == null) return;
        log.info("Reranker is running on {} ({} passages at a time, {} at most per question)",
                health.device(), health.batch_size(), candidates());
        String advice = gpuAdvice(health.device(), candidates(), health.batch_size());
        if (advice != null) log.info("{}", advice);
    }

    /**
     * Whether the reranker runs on a GPU, as it last reported; asked when not yet known. False
     * while it does not answer: a processor's pool is the one that cannot time out.
     */
    public boolean onGpu() {
        Endpoint target = target();
        if (target.onGpu == null && health(target) == null && target == node) {
            // Asked before retrieval, which sizes the pool: a node that does not answer now will
            // not score the question, so the pool is the one this server can score.
            fallback.failed("its /health does not answer");
            target = local;
            if (target.onGpu == null) health(target);
        }
        return Boolean.TRUE.equals(target.onGpu);
    }

    /** Passages retrieved per question: reranker.candidates, or the default for the device. */
    public int candidates() {
        return config.resolvedCandidates(onGpu());
    }

    /** What the reranker the next question goes to reports on /health, or null when it cannot be read. */
    RerankerHealth health() {
        return health(target());
    }

    private static RerankerHealth health(Endpoint endpoint) {
        try {
            RerankerHealth health = endpoint.healthClient.get().uri("/health").retrieve().body(RerankerHealth.class);
            if (health != null && health.device() != null) endpoint.onGpu = isCuda(health.device());
            return health;
        } catch (Exception e) {
            log.debug("Could not read the device of the reranker at {}: {}", endpoint.url, e.getMessage());
            return null;
        }
    }

    /**
     * Checks, once the application is up, that the reranker it will call can do what the
     * configuration asks — and says so as an ERROR when it cannot, because both failures below
     * are otherwise silent: reranking quietly falls back to retrieval order, or runs a minute
     * per question on a processor while the admin believes it is on the GPU.
     *
     * <p>Waits for the service in the background: a local reranker is still loading its model
     * when the application becomes ready, and a GPU node may be waking up.
     */
    @EventListener(ApplicationReadyEvent.class)
    void checkDeploymentWhenReady() {
        if (!config.isUsed()) return;
        Thread.ofVirtual().name("reranker-deployment-check").start(() -> {
            Instant deadline = Instant.now().plus(Duration.ofMinutes(5));
            while (!isHealthy()) {
                if (Instant.now().isAfter(deadline)) {
                    log.warn("Reranker at {} did not answer within 5 minutes of startup: its "
                            + "deployment could not be checked", config.getPythonUrl());
                    return;
                }
                try { Thread.sleep(5_000); } catch (InterruptedException e) { return; }
            }
            deployment().messages().forEach(message -> log.error("[Reranker] {}", message));
        });
    }

    /** What can be wrong with a running reranker for this configuration. */
    public enum Problem {
        /** It refuses the configured pool, so every question falls back to retrieval order. */
        TOO_FEW_PASSAGES,
        /** On another machine — a GPU node, the only reason to put it there — but on its processor. */
        NOT_ON_GPU
    }

    /**
     * What the running reranker reports — device ("cuda:0", "cpu") and the most passages it
     * accepts, null when they cannot be read — against the pool this configuration asks of it,
     * and what is wrong as a result (empty when nothing is).
     */
    public record Deployment(String device, Integer maxPassages, int candidates, List<Problem> problems) {

        /** The problems as log lines, for the admin reading the log rather than the panel. */
        public List<String> messages() {
            return problems.stream().map(problem -> switch (problem) {
                case TOO_FEW_PASSAGES -> String.format(Locale.ROOT,
                        "The reranker accepts at most %d passages but reranker.candidates is %d: it refuses "
                        + "every question, and Sherlock falls back to retrieval order. Set "
                        + "RERANKER_MAX_PASSAGES to at least %d where the reranker runs (restart it if "
                        + "IntraCherche starts it), or lower reranker.candidates.",
                        maxPassages, candidates, candidates);
                case NOT_ON_GPU -> String.format(Locale.ROOT,
                        "The reranker at reranker.pythonUrl runs on another machine, but on its processor "
                        + "(%s): each question takes about a minute instead of a second. The CUDA build of "
                        + "PyTorch is missing or the NVIDIA driver is not loaded — set the GPU node up again "
                        + "there (see LISEZMOI-GPU.md).",
                        device);
            }).toList();
        }
    }

    /** The deployment of the reranker the next question goes to. */
    public Deployment deployment() {
        return deployment(target());
    }

    /** The deployment of the reranker at reranker.pythonUrl. */
    public Deployment localDeployment() {
        return deployment(local);
    }

    /** The deployment of the GPU node's reranker, or null without one. */
    public Deployment nodeDeployment() {
        return node == null ? null : deployment(node);
    }

    private Deployment deployment(Endpoint endpoint) {
        RerankerHealth health = health(endpoint);
        boolean elsewhere = endpoint == node ? !Hosts.isThisMachine(endpoint.url) : !config.runsHere();
        return deployment(health, elsewhere, config.resolvedCandidates(Boolean.TRUE.equals(endpoint.onGpu)));
    }

    /** {@code elsewhere}: the reranker runs on another machine, which is there for its GPU. */
    static Deployment deployment(RerankerHealth health, boolean elsewhere, int candidates) {
        if (health == null) return new Deployment(null, null, candidates, List.of());
        List<Problem> problems = new ArrayList<>();
        if (health.max_passages() != null && health.max_passages() < candidates) {
            problems.add(Problem.TOO_FEW_PASSAGES);
        }
        if (elsewhere && health.device() != null && !isCuda(health.device())) {
            problems.add(Problem.NOT_ON_GPU);
        }
        return new Deployment(health.device(), health.max_passages(), candidates, List.copyOf(problems));
    }

    private static boolean isCuda(String device) {
        return device.toLowerCase(Locale.ROOT).startsWith("cuda");
    }

    /**
     * The advice to log, or null when the settings already suit the device. With the auto
     * values they always do; only a reranker.candidates set by hand for a processor is left.
     */
    static String gpuAdvice(String device, int candidates, Integer batchSize) {
        if (device == null || !isCuda(device)) return null;
        if (candidates >= GPU_CANDIDATES_HINT) return null;
        return String.format(Locale.ROOT,
                "This reranker is on a GPU but reranker.candidates is %d, a processor's figure. A GPU "
                + "scores %d passages in about a second (%s at a time here): set reranker.candidates "
                + "to 0 (auto) or %d to widen what Sherlock can find at no cost in time. See "
                + "INSTALL.md, \"Accélération GPU\".",
                candidates, GPU_CANDIDATES_HINT, batchSize, GPU_CANDIDATES_HINT);
    }

    private record RerankerResponse(List<Double> scores) {}

    /** The fields of /health this class reads; the service reports more, hence the annotation. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record RerankerHealth(String device, Integer batch_size, Integer max_passages) {}
}
