package eu.intracherche.intracherche.indexing.worker;

import eu.intracherche.intracherche.indexing.model.IndexTask;
import eu.intracherche.intracherche.queues.IndexTaskQueue;
import org.apache.solr.client.solrj.SolrClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.*;

/**
 * Manages a pool of {@link IndexWorker} instances that consume {@link IndexTask}
 * instances from a shared {@link IndexTaskQueue} and process them asynchronously.
 *
 * <p>The IndexWorkerManager uses a {@link ThreadPoolExecutor} with:
 * <ul>
 *   <li>Fixed core pool size of 4 threads</li>
 *   <li>A bounded {@link LinkedBlockingQueue} with capacity 100 (via IndexTaskQueue)</li>
 * </ul>
 * </p>
 *
 * <p>Each worker thread runs a dedicated IndexWorker instance that continuously
 * pulls tasks from the queue, converts them to SolrInputDocument, and submits
 * them to the appropriate Solr core.</p>
 *
 * <p>This class is designed to be managed as a Spring bean with lifecycle hooks
 * for startup and graceful shutdown.</p>
 */
public class IndexWorkerManager {

    private static final Logger log = LoggerFactory.getLogger(IndexWorkerManager.class);

    /** Default pool size for index workers. */
    private static final int DEFAULT_POOL_SIZE = 4;

    /** Default commitWithinMs value for Solr indexing. */
    private static final int DEFAULT_COMMIT_WITHIN_MS = 1000;

    private final int poolSize;
    private final int commitWithinMs;
    private final SolrClient solrClient;
    private final IndexTaskQueue indexTaskQueue;
    private final DocumentCompletionTracker completionTracker;

    private ThreadPoolExecutor executor;
    private volatile boolean started = false;

    public IndexWorkerManager(SolrClient solrClient, IndexTaskQueue indexTaskQueue,
                              DocumentCompletionTracker completionTracker) {
        this(solrClient, indexTaskQueue, DEFAULT_POOL_SIZE, DEFAULT_COMMIT_WITHIN_MS, completionTracker);
}

    public IndexWorkerManager(SolrClient solrClient, IndexTaskQueue indexTaskQueue,
                              int poolSize, int commitWithinMs,
                              DocumentCompletionTracker completionTracker) {
        this.solrClient = solrClient;
        this.indexTaskQueue = indexTaskQueue;
        this.poolSize = poolSize;
        this.commitWithinMs = commitWithinMs;
        this.completionTracker = completionTracker;
    }

    /**
     * Starts the worker pool. Creates and launches the specified number of
     * IndexWorker threads, each consuming from the shared queue.
     */
    public void start() {
        if (started) {
            log.warn("IndexWorkerManager already started. Ignoring duplicate start call.");
            return;
        }

        log.info("Starting IndexWorkerManager with {} workers...", poolSize);

        // Create a fixed thread pool
        executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(poolSize, r -> {
            Thread t = new Thread(r, "IndexWorker-" + System.currentTimeMillis());
            t.setDaemon(true); // Allow JVM to exit even if workers are running
            return t;
        });

        for (int i = 0; i < poolSize; i++) {
            final int workerIndex = i;
            QueueBoundIndexWorker worker = new QueueBoundIndexWorker(
                    "worker-" + workerIndex, solrClient, commitWithinMs, indexTaskQueue, completionTracker
            );
            executor.submit(worker);
            log.debug("Submitted IndexWorker-{} to thread pool", workerIndex);
        }

        started = true;
        log.info("IndexWorkerManager started with {} workers", poolSize);
    }

    /**
     * Gracefully shuts down the worker pool. Workers will finish processing
     * their current task before stopping.
     *
     * @param timeoutSeconds Maximum time to wait for workers to terminate.
     */
    public void stop(long timeoutSeconds) {
        if (!started) {
            log.warn("IndexWorkerManager not started. Ignoring stop call.");
            return;
        }

        log.info("Stopping IndexWorkerManager (timeout: {}s)...", timeoutSeconds);

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
        log.info("IndexWorkerManager stopped");
    }

    /**
     * Spring's @PreDestroy hook for clean shutdown.
     */
    @PreDestroy
    public void preDestroy() {
        stop(30);
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
        return poolSize;
    }

    /**
     * Returns the current size of the IndexTaskQueue.
     */
    public int getQueueSize() {
        return indexTaskQueue.size();
    }

    /**
     * A subclass of IndexWorker that is bound to a specific IndexTaskQueue
     * and pulls tasks from it directly.
     */
    private static class QueueBoundIndexWorker extends IndexWorker {

        private final IndexTaskQueue queue;

        QueueBoundIndexWorker(String name, SolrClient solrClient, int commitWithinMs,
                              IndexTaskQueue queue, DocumentCompletionTracker completionTracker) {
            super(name, solrClient, commitWithinMs, completionTracker);
            this.queue = queue;
        }

        @Override
        protected IndexTask dequeueTask() throws InterruptedException {
            return queue.take();
        }
    }
}