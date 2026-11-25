package eu.intracherche.intracherche.indexing.worker;

import eu.intracherche.intracherche.config.OcrConfig;
import eu.intracherche.intracherche.indexing.outage.ServiceOutageMonitor;
import eu.intracherche.intracherche.queues.IndexTaskQueue;
import eu.intracherche.intracherche.queues.OcrQueue;
import eu.intracherche.intracherche.services.EmbeddingService;
import eu.intracherche.intracherche.services.OcrService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.*;

/**
 * Manages a pool of {@link OcrWorker} instances that consume {@link OCRTask}
 * instances from a shared {@link OcrQueue} and process them asynchronously.
 *
 * <p>The pool size is determined at {@link #start()} time from {@code ocrConfig.pagesInFlight()}:
 * the Python OCR worker count resolved by {@code ResourceValidator} before any service starts,
 * so no Python worker ever sits idle waiting for a caller — or, with a GPU node, enough pages
 * in flight to keep its GPU busy.</p>
 *
 * <p>This class is designed to be managed as a Spring bean with lifecycle hooks
 * for startup and graceful shutdown.</p>
 */
public class OcrWorkerManager {

    private static final Logger log = LoggerFactory.getLogger(OcrWorkerManager.class);

    private final OcrService ocrService;
    private final EmbeddingService embeddingService;
    private final OcrQueue ocrQueue;
    private final IndexTaskQueue indexTaskQueue;
    private final OcrConfig ocrConfig;
    private final DocumentCompletionTracker completionTracker;
    private final ServiceOutageMonitor outageMonitor;

    private ThreadPoolExecutor executor;
    private volatile boolean started = false;

    public OcrWorkerManager(OcrService ocrService,
                            EmbeddingService embeddingService,
                            OcrQueue ocrQueue,
                            IndexTaskQueue indexTaskQueue,
                            OcrConfig ocrConfig,
                            DocumentCompletionTracker completionTracker,
                            ServiceOutageMonitor outageMonitor) {
        this.ocrService = ocrService;
        this.embeddingService = embeddingService;
        this.ocrQueue = ocrQueue;
        this.indexTaskQueue = indexTaskQueue;
        this.ocrConfig = ocrConfig;
        this.completionTracker = completionTracker;
        this.outageMonitor = outageMonitor;
    }

    /**
     * Starts the worker pool. Creates and launches the specified number of
     * OcrWorker threads, each consuming from the shared queue.
     */
    public void start() {
        if (started) {
            log.warn("OcrWorkerManager already started. Ignoring duplicate start call.");
            return;
        }

        int poolSize = Math.max(1, ocrConfig.pagesInFlight());
        log.info("Starting OcrWorkerManager with {} workers...", poolSize);

        executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(poolSize, r -> {
            Thread t = new Thread(r, "OcrWorker-" + System.currentTimeMillis());
            t.setDaemon(true);
            return t;
        });

        for (int i = 0; i < poolSize; i++) {
            final int workerIndex = i;
            OcrWorker worker = new OcrWorker(
                    "ocr-worker-" + workerIndex, ocrService, embeddingService, ocrQueue, indexTaskQueue, completionTracker,
                    outageMonitor
            );
            executor.submit(worker);
            log.debug("Submitted OcrWorker-{} to thread pool", workerIndex);
        }

        started = true;
        log.info("OcrWorkerManager started with {} workers", poolSize);
    }

    /**
     * Gracefully shuts down the worker pool. Workers will finish processing
     * their current task before stopping.
     *
     * @param timeoutSeconds Maximum time to wait for workers to terminate.
     */
    public void stop(long timeoutSeconds) {
        if (!started) {
            log.warn("OcrWorkerManager not started. Ignoring stop call.");
            return;
        }

        log.info("Stopping OcrWorkerManager (timeout: {}s)...", timeoutSeconds);

        // Interrupt all workers immediately (wakes threads blocked on queue.take())
        if (executor != null) {
            executor.shutdownNow();
            try {
                if (!executor.awaitTermination(timeoutSeconds, TimeUnit.SECONDS)) {
                    log.error("Workers did not terminate after forced shutdown");
                }
            } catch (InterruptedException e) {
                log.warn("Interrupted while waiting for workers to terminate");
                Thread.currentThread().interrupt();
            }
        }

        started = false;
        log.info("OcrWorkerManager stopped");
    }

    /**
     * Spring's @PreDestroy hook for clean shutdown.
     */
    @PreDestroy
    public void preDestroy() {
        stop(ocrConfig.getReadTimeoutSeconds());
    }

    /**
     * Returns whether this manager has been started.
     */
    public boolean isStarted() {
        return started;
    }

    /**
     * Returns the number of worker threads in the pool.
     */
    public int getPoolSize() {
        return executor != null ? executor.getCorePoolSize() : 0;
    }

    /**
     * Returns the current size of the OcrQueue.
     */
    public int getQueueSize() {
        return ocrQueue.size();
    }
}