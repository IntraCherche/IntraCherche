package eu.intracherche.intracherche.services;

import eu.intracherche.intracherche.common.events.FileDiscoveredEvent;
import eu.intracherche.intracherche.config.ApplicationConfig;
import eu.intracherche.intracherche.config.IndexedDirConfig;
import eu.intracherche.intracherche.config.MountType;
import eu.intracherche.intracherche.discovery.AbstractScanner;
import eu.intracherche.intracherche.discovery.DiscoveryRunner;
import eu.intracherche.intracherche.queues.DiscoveryQueue;
import eu.intracherche.intracherche.registry.FileRecord;
import eu.intracherche.intracherche.registry.FileRegistry;
import eu.intracherche.intracherche.registry.RegistryIngestWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;

/**
 * Service class that coordinates the complete file registry feeding process.
 * This service provides a clean API for starting the complete feeding process
 * without requiring direct manipulation of individual components.
 */
public class FileRegistryFeedingService {

    private static final Logger log = LoggerFactory.getLogger(FileRegistryFeedingService.class);

    /** How long the ingest worker may go without writing a single file before it counts as stuck. */
    private static final int INGEST_STALL_SECONDS = 30;

    private final AbstractScanner scanner;
    private final FileRegistry fileRegistry;
    private final DiscoveryQueue discoveryQueue;
    private final ApplicationConfig applicationConfig;
    private final ExecutorService executorService;
    private final int ingestStallSeconds;



    private volatile boolean isRunning = false;
    private volatile Future<?> discoveryFuture = null;
    private volatile Future<?> ingestFuture = null;
    private volatile long currentScanId = -1;

    public FileRegistryFeedingService(
            AbstractScanner scanner,
            FileRegistry fileRegistry,
            DiscoveryQueue discoveryQueue,
            ApplicationConfig applicationConfig
    ) {
        this(scanner, fileRegistry, discoveryQueue, applicationConfig, INGEST_STALL_SECONDS);
    }

    FileRegistryFeedingService(
            AbstractScanner scanner,
            FileRegistry fileRegistry,
            DiscoveryQueue discoveryQueue,
            ApplicationConfig applicationConfig,
            int ingestStallSeconds
    ) {
        this.ingestStallSeconds = ingestStallSeconds;
        this.scanner = scanner;
        this.fileRegistry = fileRegistry;
        this.discoveryQueue = discoveryQueue;
        this.applicationConfig = applicationConfig;
        this.executorService = Executors.newCachedThreadPool();
    }

    /**
     * Starts the complete file registry feeding process.
     * This method:
     * 1. Begins a new scan in the registry
     * 2. Starts the registry ingest worker in a separate thread
     * 3. Starts the directory scanner in a separate thread
     * 4. Waits for both processes to complete
     *
     * @param dirConfig the root directory to scan
     * @throws IOException          if an I/O error occurs during directory scanning
     * @throws InterruptedException if the thread is interrupted during execution
     */
    public void startFeeding(IndexedDirConfig dirConfig) throws IOException, InterruptedException, SQLException {
        if (isRunning) {
            log.warn("Feeding process is already running");
            return;
        }

        isRunning = true;
        String sourceLabel = dirConfig.getSmbServer();
        log.info("Starting feeding process for source: {}", sourceLabel);

        try {
            // Begin a new scan in the registry
            currentScanId = fileRegistry.beginScan();
            log.info("Started new scan with ID: {}", currentScanId);

            // Start the registry ingest worker
            RegistryIngestWorker ingestWorker = new RegistryIngestWorker(discoveryQueue, fileRegistry, currentScanId);
            ingestFuture = executorService.submit(ingestWorker);
            log.info("Started registry ingest worker");

            // Start the directory scanner
            DiscoveryRunner discoveryRunner = new DiscoveryRunner(scanner, dirConfig);
            discoveryFuture = executorService.submit(discoveryRunner);
            log.info("Started {} scanner", dirConfig.getMountType());

            // Wait for discovery to complete
            if (discoveryFuture != null) {
                try {
                    discoveryFuture.get(); // Wait for discovery to complete
                } catch (ExecutionException e) {
                    throw new SourceUnreachableException(e);
                }
            }


            // Signal the ingest worker to stop by adding a sentinel value to the queue
            // This will cause the RegistryIngestWorker to exit its loop
            try {
                // Add a sentinel event to signal the ingest worker to stop
                // We'll use a special path to indicate shutdown
                discoveryQueue.publish(new FileDiscoveredEvent(
                        Path.of("__SHUTDOWN__"),
                        null,
                        null,                    // smbHost
                        0L,                     // size (long)
                        Instant.EPOCH,
                        Instant.now(),
                        Collections.emptySet(), // allowSids
                        Collections.emptySet(), // denySids
                        "",                     // hash
                        false                   // isSmb
                ));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Interrupted while trying to signal shutdown to ingest worker");
            }

            // Wait for ingest worker to finish processing any remaining items
            if (ingestFuture != null) {
                awaitIngest();
            }

        } catch (Exception e) {
            log.error("Error during file registry feeding process", e);
            // Clean up on error
            cleanup();
            throw e;
        } finally {
            isRunning = false;
//            logRegistryContents();
        }
    }

    /**
     * Waits for the ingest worker to drain the queue, for as long as it keeps draining it.
     *
     * The scanner fills the queue far faster than the worker writes the registry, so when
     * discovery ends the queue can hold its full capacity: 10 000 files at ~5 ms each is
     * close to a minute. A fixed deadline failed the whole source on large shares while
     * the worker was doing fine; only a worker that stops making progress is a failure.
     */
    private void awaitIngest() throws IOException, InterruptedException {
        int pending = discoveryQueue.size();
        while (true) {
            try {
                ingestFuture.get(ingestStallSeconds, TimeUnit.SECONDS);
                return;
            } catch (ExecutionException e) {
                throw new IOException("Ingest process failed", e);
            } catch (TimeoutException e) {
                int stillPending = discoveryQueue.size();
                if (stillPending < pending) {
                    log.debug("Registry ingest still draining: {} file(s) pending", stillPending);
                    pending = stillPending;
                    continue;
                }
                ingestFuture.cancel(true);
                // Left in the queue, these events (and the shutdown sentinel among them)
                // would be read by the next scan's worker under the wrong scan ID.
                discoveryQueue.clear();
                throw new IOException("Enregistrement des fichiers découverts bloqué : aucun enregistré en "
                        + ingestStallSeconds + " s, " + stillPending + " en attente");
            }
        }
    }

    /**
     * Thrown when the scanner could not read the source: the share or directory is down,
     * refused or missing. Kept apart from the registry's own failures, which happen after
     * the source was read and must not be reported as an unreachable source.
     */
    public static class SourceUnreachableException extends IOException {
        SourceUnreachableException(Throwable cause) {
            super("Discovery process failed", cause);
        }
    }

    /**
     * Stops the feeding process if it's currently running.
     * This method will interrupt any running threads and clean up resources.
     */
    public void stopFeeding() {
        log.info("Stopping file registry feeding process");
        isRunning = false;

        if (discoveryFuture != null && !discoveryFuture.isDone()) {
            discoveryFuture.cancel(true);
        }

        if (ingestFuture != null && !ingestFuture.isDone()) {
            ingestFuture.cancel(true);
        }

        cleanup();
    }

    /**
     * Cleans up resources and resets the service state.
     */
    private void cleanup() {
        if (currentScanId != -1) {
            try {
                fileRegistry.endScan(currentScanId);
                log.info("Cleaned up scan ID: {}", currentScanId);
            } catch (Exception e) {
                log.warn("Error during cleanup of scan ID: {}", currentScanId, e);
            }
            currentScanId = -1;
        }
    }

    /**
     * Checks if the feeding process is currently running.
     *
     * @return true if the feeding process is running, false otherwise
     */
    public boolean isRunning() {
        return isRunning;
    }

    /**
     * Shuts down the executor service and releases all resources.
     * This should be called when the service is no longer needed.
     */
    public void shutdown() {
        stopFeeding();
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Logs the current contents of the file registry.
     * This method retrieves all file records from the registry and logs them.
     */
    private void logRegistryContents() {
        try {
            List<FileRecord> records = fileRegistry.findAll();
            log.info("File Registry Contents ({} files):", records.size());

            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            ZoneId zone = ZoneId.systemDefault();

            for (FileRecord record : records) {
                String formattedTime = LocalDateTime.ofInstant(
                        Instant.ofEpochMilli(record.mtime()), zone
                ).format(formatter);

                String formattedLastSeenTime = LocalDateTime.ofInstant(
                        Instant.ofEpochMilli(record.lastSeen()), zone
                ).format(formatter);

                // We only report what is in the DB.
                // We distinguish by path prefix or a flag so the admin knows the source.
                String sourcePrefix = record.isSmb() ? "[SMB: " + record.smbShareName() + "]" : "[LOCAL]";

                log.info("  - {} {} ({} bytes, mtime: {}, lastSeen: {})",
                        sourcePrefix,
                        record.path(),
                        record.size(),
                        formattedTime,
                        formattedLastSeenTime
                );
            }
        } catch (SQLException e) {
            log.error("Failed to retrieve registry contents: {}", e.getMessage());
        }
    }
}
