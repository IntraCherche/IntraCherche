package eu.intracherche.intracherche.process;

import eu.intracherche.intracherche.config.*;
import eu.intracherche.intracherche.services.*;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class PythonServiceManager {
    private static final Logger log = LoggerFactory.getLogger(PythonServiceManager.class);

    private final OcrConfig ocrConfig;
    private final NerConfig nerConfig;
    private final EmbeddingConfig embeddingConfig;
    private final RerankerConfig rerankerConfig;

    private final OcrService ocrService;
    private final NerService nerService;
    private final EmbeddingService embeddingService;
    private final RerankerService rerankerService;

    private final ResourceValidator resourceValidator;
    private final ResourceConfig resourceConfig;

    // Models live under ./models in a packaged install (self-contained under the
    // working directory, e.g. /opt/intracherche/models) or under ~/.IntraCherche/models
    // in a dev checkout. Resolve whichever exists; keep absolute so the path stays
    // correct when passed to a subprocess running from its own service directory.
    private static final Path MODELS_HOME = resolveModelsHome();

    private static Path resolveModelsHome() {
        Path packaged = Path.of("models").toAbsolutePath().normalize();
        if (Files.isDirectory(packaged)) {
            return packaged;
        }
        return Path.of(System.getProperty("user.home"), ".IntraCherche", "models");
    }

    private final Map<String, PythonServiceProcess> processes = new LinkedHashMap<>();
    private final Set<String> restarting = ConcurrentHashMap.newKeySet();

    public PythonServiceManager(OcrConfig ocrConfig, NerConfig nerConfig,
                                EmbeddingConfig embeddingConfig, RerankerConfig rerankerConfig,
                                OcrService ocrService, NerService nerService,
                                EmbeddingService embeddingService, RerankerService rerankerService,
                                ResourceValidator resourceValidator, ResourceConfig resourceConfig) {
        this.ocrConfig = ocrConfig;
        this.nerConfig = nerConfig;
        this.embeddingConfig = embeddingConfig;
        this.rerankerConfig = rerankerConfig;
        this.ocrService = ocrService;
        this.nerService = nerService;
        this.embeddingService = embeddingService;
        this.rerankerService = rerankerService;
        this.resourceValidator = resourceValidator;
        this.resourceConfig = resourceConfig;
    }

    @PostConstruct
    public void startAll() {
        resourceValidator.validateAndAllocate();
        // OCR, NER and embedding do mostly indexing work: they run at lower CPU priority so
        // that searches win when the CPU is short. The reranker only serves Sherlock questions.
        int indexingNice = resourceConfig.getIndexingServicesNice();
        register("ocr",       ocrConfig.getPythonUrl(),       ocrConfig.getWorkers(),       Map.of(), indexingNice);
        register("ner",       nerConfig.getPythonUrl(),       nerConfig.getWorkers(),       env("NER_MODEL",       nerConfig.getNerModel()), indexingNice);
        register("embedding", embeddingConfig.getPythonUrl(), embeddingConfig.getWorkers(), env("EMBEDDING_MODEL", modelPath("embedding").toString()), indexingNice);
        // RERANKER_MAX_PASSAGES follows reranker.candidates: the service refuses a larger request
        // rather than scoring part of it, because a partial reply makes Java discard the scores
        // and fall back to retrieval order — reranking off, silently, for every question. With
        // the auto count it is a GPU's, the device being unknown until the service is up.
        // RERANKER_BATCH_SIZE is only passed when set: otherwise the service picks it for its device.
        // A reranker on a GPU node is the node's to run: starting one here too would load the
        // model for nothing, and requireModel would demand a copy this server does not need.
        if (rerankerConfig.startsHere()) {
            Map<String, String> rerankerEnv = new LinkedHashMap<>(Map.of(
                    "RERANKER_MODEL",        modelPath("reranker").toString(),
                    "RERANKER_THREADS",      String.valueOf(rerankerConfig.resolvedThreads()),
                    "RERANKER_MAX_PASSAGES", String.valueOf(rerankerConfig.maxPassagesHere())));
            if (rerankerConfig.getBatchSize() > 0) {
                rerankerEnv.put("RERANKER_BATCH_SIZE", String.valueOf(rerankerConfig.getBatchSize()));
            }
            register("reranker",  rerankerConfig.getPythonUrl(),  rerankerConfig.getWorkers(),  rerankerEnv, 0);
        } else if (!rerankerConfig.isUsed()) {
            log.info("reranker: switched off (reranker.enabled or llm.enabled is false) — not started");
        } else {
            log.info("reranker: runs at {} — not started here", rerankerConfig.getPythonUrl());
        }

        requireModel("embedding");
        requireModel("reranker");

        for (Map.Entry<String, PythonServiceProcess> entry : processes.entrySet()) {
            String name = entry.getKey();
            Thread.ofVirtual().name("start-" + name).start(() -> entry.getValue().start());
        }
    }

    @PreDestroy
    public void stopAll() {
        processes.values().forEach(PythonServiceProcess::stop);
    }

    public void restart(String name) {
        PythonServiceProcess proc = processes.get(name);
        if (proc == null) throw new IllegalArgumentException("Service not managed by IntraCherche: " + name);
        restarting.add(name);
        Thread.ofVirtual().name("restart-" + name).start(() -> {
            try {
                proc.stop();
                proc.start();
            } finally {
                restarting.remove(name);
            }
        });
    }

    public boolean isManaged(String name) {
        return processes.containsKey(name);
    }

    public boolean isRestarting(String name) {
        return restarting.contains(name);
    }

    public void waitUntilAllReady(Duration timeout) {
        if (processes.isEmpty()) return;
        Instant deadline = Instant.now().plus(timeout);
        log.info("Waiting for Python services to become ready (timeout {}s)...", timeout.getSeconds());
        while (Instant.now().isBefore(deadline)) {
            if (allHealthy()) {
                log.info("All Python services are ready");
                return;
            }
            try { Thread.sleep(3_000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            log.info("Waiting for Python services... ({} s remaining)",
                Duration.between(Instant.now(), deadline).getSeconds());
        }
        log.warn("Python services did not all become ready within {}s — continuing anyway", timeout.getSeconds());
    }

    private boolean allHealthy() {
        boolean ok = true;
        if (processes.containsKey("ocr"))       ok &= ocrService.isHealthy();
        if (processes.containsKey("ner"))       ok &= nerService.isHealthy();
        if (processes.containsKey("embedding")) ok &= embeddingService.isHealthy();
        if (processes.containsKey("reranker"))  ok &= rerankerService.isHealthy();
        return ok;
    }

    private void register(String name, String pythonUrl, int workers, Map<String, String> env, int nice) {
        Path dir = serviceDir(name);
        if (dir == null) {
            log.info("{}: service directory not found (looked under ./python-services/ and ./) — service will not be managed", name);
            return;
        }
        int port = URI.create(pythonUrl).getPort();
        boolean packaged = dir.startsWith("python-services");
        processes.put(name, new PythonServiceProcess(name, dir, port, workers, env, nice, packaged));
    }

    /**
     * Resolves the directory holding a Python service's code. A packaged install
     * keeps them under ./python-services/{name}-service; a dev checkout has them at
     * ./{name}-service. Returns the first that exists, or null if neither does.
     */
    private Path serviceDir(String name) {
        Path packaged = Path.of("python-services", name + "-service");
        if (Files.exists(packaged)) return packaged;
        Path dev = Path.of(name + "-service");
        if (Files.exists(dev)) return dev;
        return null;
    }

    private Map<String, String> env(String key, String value) {
        if (value == null || value.isBlank()) return Map.of();
        return Map.of(key, value);
    }

    private static Path modelPath(String name) {
        return MODELS_HOME.resolve(name);
    }

    private void requireModel(String service) {
        if (!processes.containsKey(service)) return; // service directory absent — not managed
        Path path = modelPath(service);
        if (Files.exists(path)) return;
        throw new MissingModelException(service, path);
    }

    public static class MissingModelException extends RuntimeException {
        public MissingModelException(String service, Path path) {
            super(buildMessage(service, path));
        }

        private static String buildMessage(String service, Path path) {
            return String.format("""

                [STARTUP FAILURE] Model for '%s' not found at: %s

                  Re-run the IntraCherche installer, or place the model files manually:

                    %s/   — intfloat/multilingual-e5-base
                    %s/   — BAAI/bge-reranker-v2-m3

                  To download the models once (requires internet access):

                    cd embedding-service && uv run python -c \
                "from huggingface_hub import snapshot_download; \
                snapshot_download('intfloat/multilingual-e5-base', local_dir='%s')"

                    cd reranker-service && uv run python -c \
                "from huggingface_hub import snapshot_download; \
                snapshot_download('BAAI/bge-reranker-v2-m3', local_dir='%s')"
                """,
                service, path,
                MODELS_HOME.resolve("embedding"),
                MODELS_HOME.resolve("reranker"),
                MODELS_HOME.resolve("embedding"),
                MODELS_HOME.resolve("reranker"));
        }
    }
}
