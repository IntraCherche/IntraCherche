package eu.intracherche.intracherche.app;

import eu.intracherche.intracherche.config.*;
import eu.intracherche.intracherche.discovery.AbstractScanner;
import eu.intracherche.intracherche.discovery.SmbClientManager;
import eu.intracherche.intracherche.discovery.SmbjScanner;
import eu.intracherche.intracherche.queues.DiscoveryQueue;
import eu.intracherche.intracherche.queues.OcrQueue;
import eu.intracherche.intracherche.registry.DatabaseDiagnosticTool;
import eu.intracherche.intracherche.registry.FileRegistry;
import eu.intracherche.intracherche.schedulers.IndexingScheduler;
import eu.intracherche.intracherche.indexing.error.IndexingErrorDao;
import eu.intracherche.intracherche.indexing.worker.DocumentCompletionTracker;
import eu.intracherche.intracherche.indexing.worker.IndexingProgressTracker;
import eu.intracherche.intracherche.services.FileIndexingService;
import eu.intracherche.intracherche.services.FileRegistryFeedingService;
import eu.intracherche.intracherche.services.IndexService;
import eu.intracherche.intracherche.services.SolrCleanupService;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.request.CoreAdminRequest;
import org.apache.solr.client.solrj.response.CoreAdminResponse;
import org.apache.solr.common.util.NamedList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.sql.SQLException;
import java.sql.Statement;

import static eu.intracherche.intracherche.common.constants.DatabaseConstants.FILE_REGISTRY_TABLE;

@Service
public class ApplicationOrchestrator {
    private static final Logger log = LoggerFactory.getLogger(ApplicationOrchestrator.class);


    private final FileIndexingService indexingService;
    private final SolrCleanupService cleanupService;
    private final FileRegistry registry; // Also injected
    private final ApplicationConfig applicationConfig;
    private final IndexesConfig indexesConfig;
    private final DiscoveryQueue discoveryQueue;
    private final SmbClientManager smbClientManager;
    private final SolrClient solrClient;
    private final IndexService indexService;
    private final DocumentCompletionTracker documentCompletionTracker;
    private final IndexingProgressTracker progressTracker;
    private final OcrConfig ocrConfig;
    private final OcrQueue ocrQueue;
    private final IndexingScheduler indexingScheduler;

    private volatile boolean paused = false;

    public ApplicationOrchestrator(
                                    SmbClientManager smbClientManager,
                                    FileIndexingService indexingService,
                                   FileRegistry registry,
                                   ApplicationConfig applicationConfig,
                                   IndexesConfig indexesConfig,
                                   DiscoveryQueue discoveryQueue,
                                    SolrCleanupService cleanupService,
                                    SolrClient solrClient,
                                    IndexService indexService,
                                    DocumentCompletionTracker documentCompletionTracker,
                                    IndexingProgressTracker progressTracker,
                                    OcrConfig ocrConfig,
                                    OcrQueue ocrQueue,
                                    IndexingScheduler indexingScheduler) {
        this.smbClientManager = smbClientManager;
        this.indexingService = indexingService;
        this.cleanupService = cleanupService;
        this.registry = registry;
        this.applicationConfig = applicationConfig;
        this.indexesConfig = indexesConfig;
        this.discoveryQueue = discoveryQueue;
        this.solrClient = solrClient;
        this.indexService = indexService;
        this.documentCompletionTracker = documentCompletionTracker;
        this.progressTracker = progressTracker;
        this.ocrConfig = ocrConfig;
        this.ocrQueue = ocrQueue;
        this.indexingScheduler = indexingScheduler;
    }

    /**
     * Starts the continuous indexing loop. Runs one full cycle, then pauses for
     * {@code indexing-config.cycleIntervalMinutes} before starting the next cycle.
     * Stops cleanly when the thread is interrupted (e.g. on application shutdown).
     */
    public void pause() {
        paused = true;
        indexingService.pauseProcessing();
        int drained = ocrQueue.drain().size();
        documentCompletionTracker.cancelAllPending();
        log.info("Indexing paused by admin; {} queued OCR task(s) discarded (will retry next run)", drained);
    }
    public void resume() {
        paused = false;
        indexingService.resumeProcessing();
        log.info("Indexing resumed by admin");
    }
    public boolean isPaused() { return paused; }

    @Async
    public void runIndexing() {
        long pauseMs = applicationConfig.getIndexingConfig().cycleIntervalMs();
        log.info("Continuous indexing started. Pause between cycles: {} ms", pauseMs);

        // One-shot cleanup for registries written before 3.1.0: deleting an index stripped
        // its memberships without deleting its files, so those rows stayed in the registry
        // and were retried every cycle against a share no index covers any more.
        try {
            long orphans = registry.countOrphanFiles();
            if (orphans > 0) {
                log.warn("Registry holds {} file(s) belonging to no index (left over from deleted indexes) — deleting them", orphans);
                log.info("Deleted {} orphaned registry entry/entries", registry.deleteOrphanFiles());
            }
        } catch (Exception e) {
            log.error("Could not purge orphaned registry entries: {}", e.getMessage(), e);
        }

        // One-shot recovery of the files the old markFailed() dropped: it set indexed_at on
        // failure, so an outage's casualties were never retried and showed up as indexed in
        // every count. Runs after the purge above, so rows about to be deleted are skipped.
        try {
            int requeued = registry.requeueFailedFiles();
            if (requeued > 0) {
                log.warn("Queued again {} file(s) that a previous failure had marked as indexed; they will be retried this cycle", requeued);
            }
        } catch (Exception e) {
            log.error("Could not queue previously failed files again: {}", e.getMessage(), e);
        }
        // A limit raised since the last start (indexing-config.maxFileSizeMb / maxTextChars):
        // the files it had cut short are indexed again, in full or up to the new limit.
        try {
            IndexingConfig limits = applicationConfig.getIndexingConfig();
            int requeued = registry.requeueLimitedFiles(limits.maxFileSizeBytes(), limits.maxTextChars());
            if (requeued > 0) {
                log.info("{} fichier(s) limités par une taille ou un volume de texte désormais relevés seront réindexés", requeued);
            }
        } catch (Exception e) {
            log.error("Could not queue again the files a former limit cut short: {}", e.getMessage(), e);
        }
        // A heap raised since the files that crashed the application were set aside: they are read again.
        try {
            int requeued = registry.requeueCrashLimitedFiles(Runtime.getRuntime().maxMemory() / (1024L * 1024L));
            if (requeued > 0) {
                log.info("{} fichier(s) écartés faute de mémoire seront relus, la mémoire de l'application ayant été augmentée", requeued);
            }
        } catch (Exception e) {
            log.error("Could not queue again the files set aside for lack of memory: {}", e.getMessage(), e);
        }
        while (!Thread.currentThread().isInterrupted()) {
            try {
                while (paused) {
                    Thread.sleep(2000);
                }
                runOneCycle();
                log.info("Indexing cycle complete. Next cycle in {} ms.", pauseMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Indexing cycle failed, will retry in {} ms: {}", pauseMs, e.getMessage(), e);
            }
            try {
                Thread.sleep(pauseMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        log.info("Indexing loop stopped.");
    }

    private void runOneCycle() throws SQLException, IOException, InterruptedException {
        log.info("Starting indexing cycle...");
        log.info("Running on virtual thread: {}", Thread.currentThread().isVirtual());

        // Prime the manager with all known SMB credentials
        for (IndexConfig indexConfig : indexesConfig.getIndexes()) {
            for (IndexedDirConfig dirConfig : indexConfig.getIndexedDirs()) {
                if (dirConfig.getMountType() == MountType.SMB) {
                    smbClientManager.registerConfig(dirConfig);
                }
            }
        }

        // Problems recorded against a source the configuration no longer names — a share
        // whose address has been corrected, an index that has been dropped — are cleared
        // here: nothing reads that source again, so nothing else would ever clear them.
        // A paused index is not read either: its last verdict would stay on screen for as
        // long as the pause lasts, however long ago the share came back. On resume, the
        // first attempt records the problem again if there still is one.
        List<IndexingErrorDao.Source> configuredSources = new ArrayList<>();
        for (IndexConfig indexConfig : indexesConfig.getIndexes()) {
            if (Status.DELETE.equals(indexConfig.getStatus()) && indexConfig.isConfirmDelete()) continue;
            if (Status.PAUSED.equals(indexConfig.getStatus())) continue;
            for (IndexedDirConfig dirConfig : indexConfig.getIndexedDirs()) {
                configuredSources.add(new IndexingErrorDao.Source(indexConfig.getCoreName(), sourceLabel(dirConfig)));
            }
        }
        progressTracker.pruneSourceFailures(configuredSources);

        ensureActiveCores();

        progressTracker.startRound();
        try {
            indexSources();
        } finally {
            progressTracker.endRound();
        }

        // Close SMB sessions while the TCP connection is still alive so smbj can
        // send a clean LOGOFF. Without this the server closes the idle connection
        // first and smbj logs a TransportException timeout in its PacketReader thread.
        smbClientManager.closeSessions();
        indexingScheduler.onIndexingCycleComplete();
    }

    /**
     * Creates the Solr core of every active index before any document is routed to it.
     * Cores are otherwise created when the first file of an index is reached, so an index
     * queued behind a large one stays invisible in the sidebar (which lists existing cores)
     * until then. Paused and deleted indexes are left alone: a pause must not create a core,
     * and a deletion must not be undone. A failure is logged and does not stop the cycle:
     * the indexer retries creation when it reaches the index.
     */
    void ensureActiveCores() {
        for (IndexConfig indexConfig : indexesConfig.getIndexes()) {
            if (!Status.ACTIVE.equals(indexConfig.getStatus())) continue;
            String coreName = indexConfig.getCoreName();
            try {
                if (!indexingService.createCoreIfNotExists(coreName)) {
                    log.warn("Core '{}' could not be created ahead of indexing; will retry when the index is reached.", coreName);
                }
            } catch (RuntimeException e) {
                log.warn("Core '{}' could not be created ahead of indexing: {}", coreName, e.getMessage());
            }
        }
    }

    /** Discovers, cleans up and indexes each directory of each index — one cycle per directory. */
    private void indexSources() {
        // Process each index and its directories
        for (IndexConfig indexConfig : indexesConfig.getIndexes()) {
            String coreName = indexConfig.getCoreName();
            log.info("Processing index: {}", coreName);

            Status status = indexConfig.getStatus();
            if (Status.PAUSED.equals(status)) {
                log.info("Index {} is paused, skipping discovery and indexing", coreName);
                continue;
            }

            if (Status.DELETE.equals(status) && indexConfig.isConfirmDelete()) {
                log.info("Index {} is marked for deletion, removing from Solr and registry", coreName);
                deleteIndex(coreName, indexConfig);
                continue;
            }

            Set<String> allowedExtensions = indexConfig.resolvedAllowedExtensions();
            log.info("Index {} — allowed extensions: {}", coreName, allowedExtensions);

            for (IndexedDirConfig dirConfig : indexConfig.getIndexedDirs()) {

                AbstractScanner scanner = new SmbjScanner(discoveryQueue, smbClientManager, allowedExtensions);

                log.info("Processing {} for index: {}", dirConfig.getMountType(), coreName);

                FileRegistryFeedingService feedingService = new FileRegistryFeedingService(
                        scanner, registry, discoveryQueue, applicationConfig);

                String source = sourceLabel(dirConfig);

                try {
                    // 1. DISCOVERY / FEEDING
                    try {
                        feedingService.startFeeding(dirConfig);
                        progressTracker.clearSourceFailure(coreName, source);
                    } catch (FileRegistryFeedingService.SourceUnreachableException e) {
                        // A source that cannot be opened at all yields no per-file error:
                        // record it against the source itself, or the panel reports an
                        // empty registry and "tous les fichiers sont à jour" while the
                        // share has not been read once.
                        progressTracker.recordSourceFailure(coreName, source, rootCauseMessage(e));
                        throw e;
                    } catch (Exception e) {
                        // The source was read; what failed is recording it in the registry.
                        // Reporting it as unreachable sends the admin to check a server
                        // that is up.
                        progressTracker.clearSourceFailure(coreName, source);
                        progressTracker.recordFeedingFailure(coreName, source, rootCauseMessage(e));
                        throw e;
                    }

                    // 2. CLEANUP OF REMOVED FILES ON SHARES
                    if (dirConfig.getMountType() == MountType.SMB) {
                        int hours = applicationConfig.getRegistryConfig().getStalledFilesThresholdHours();
                        int safetyValve = applicationConfig.getRegistryConfig().getSafetyValveThresholdPercent();
                        cleanupService.cleanStalledFiles(coreName, dirConfig.getSmbShare(), hours, safetyValve);
                    }

                    // 3. INDEXING
                    indexingService.processFilesToIndex();
                    documentCompletionTracker.waitForCompletion(ocrConfig.getMaxOcrWaitMinutes() * 60L);

                } catch (Exception e) {
                    log.error("Error during feeding process for index {}: {}", coreName, e.getMessage(), e);
                } finally {
                    progressTracker.endCycle();
                    feedingService.shutdown();
                }
            }
        }
    }

//    /**
//     * Creates an empty SQLite database file if it doesn't already exist
//     */
//    private static void createDatabaseIfNotExists(Path dbPath) {
//        try {
//            // Check if the file already exists
//            if (!dbPath.toFile().exists()) {
//                // Create parent directories if they don't exist
//                dbPath.getParent().toFile().mkdirs();
//
//                // Create empty database file by establishing a connection
//                // This will create the file if it doesn't exist
//                try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath())) {
//                    // Connection is established, which creates the file if it doesn't exist
//                    log.info("Created new database file at: " + dbPath.toAbsolutePath());
//                }
//            } else {
//                log.info("Database file already exists at: " + dbPath.toAbsolutePath());
//            }
//        } catch (Exception e) {
//            log.error("Error creating database file: " + e.getMessage());
//            e.printStackTrace();
//        }
//    }

    /** How a source is named in the panel's problem list: a UNC path, or the local path. */
    private static String sourceLabel(IndexedDirConfig dirConfig) {
        return "\\\\" + dirConfig.getSmbServer() + "\\" + dirConfig.getSmbShare();
    }

    /**
     * The deepest cause's message. The wrappers on the way up say nothing an admin can
     * act on ("Discovery process failed"); the bottom one names the host and the reason.
     */
    private static String rootCauseMessage(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message != null && !message.isBlank() ? message : cause.getClass().getSimpleName();
    }

    /**
     * Delete the Solr core and remove entries from the file registry for the given index.
     * This method is called when an index has status "delete" and confirmDelete is true.
     */
    private void deleteIndex(String coreName, IndexConfig indexConfig) {
        try {
            log.info("Deleting Solr core: {}", coreName);
            
            // 1. Delete the Solr core
            boolean coreDeleted = indexingService.deleteCoreIfExists(coreName);
            if (!coreDeleted) {
                log.warn("Failed to delete Solr core {}: skipping registry cleanup to maintain consistency", coreName);
                return;
            } else {
                log.info("Successfully deleted Solr core: {}", coreName);
            }
            
            // 2. Remove entries from the file registry for this core
            // Call the registry method to clean up entries for this core
            log.info("Removing registry entries for core: {}", coreName);
            registry.removeIndexMembershipAndDeleteOrphans(coreName);
            log.info("Registry cleanup for core {} completed", coreName);
            
        } catch (Exception e) {
            log.error("Error deleting index {}: {}", coreName, e.getMessage(), e);
        }
    }
}
