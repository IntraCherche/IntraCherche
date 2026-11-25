package eu.intracherche.intracherche.services;

import eu.intracherche.intracherche.indexing.MemoryHints;
import org.springframework.beans.factory.annotation.Autowired;
import eu.intracherche.intracherche.indexing.parser.TextBudget;
import eu.intracherche.intracherche.indexing.parser.UnparsableDocumentException;
import eu.intracherche.intracherche.config.IndexingConfig;
import eu.intracherche.intracherche.config.ApplicationConfig;
import com.hierynomus.smbj.share.DiskShare;
import eu.intracherche.intracherche.common.ContentSource;
import eu.intracherche.intracherche.config.EmbeddingConfig;
import eu.intracherche.intracherche.config.IndexConfig;
import eu.intracherche.intracherche.config.IndexesConfig;
import eu.intracherche.intracherche.config.NerConfig;
import eu.intracherche.intracherche.config.SolrConfig;
import eu.intracherche.intracherche.discovery.LocalContentSource;
import eu.intracherche.intracherche.discovery.SmbClientManager;
import eu.intracherche.intracherche.discovery.SmbContentSource;
import eu.intracherche.intracherche.indexing.enrichment.DocumentEnricher;
import eu.intracherche.intracherche.indexing.enrichment.LanguageDetectorEnricher;
import eu.intracherche.intracherche.indexing.enrichment.LocationNerEnricher;
import eu.intracherche.intracherche.indexing.outage.ServiceOutageMonitor;
import eu.intracherche.intracherche.indexing.outage.ServiceUnavailableException;
import eu.intracherche.intracherche.indexing.enrichment.VectorEnricher;
import eu.intracherche.intracherche.indexing.error.IndexingException;
import eu.intracherche.intracherche.indexing.model.IndexedDocument;
import eu.intracherche.intracherche.indexing.parser.DocumentParser;
import eu.intracherche.intracherche.indexing.parser.IndexedSection;
import eu.intracherche.intracherche.indexing.parser.ParseResult;
import eu.intracherche.intracherche.indexing.parser.TikaDocumentParser;
import eu.intracherche.intracherche.services.NerService;
import eu.intracherche.intracherche.indexing.pipeline.EnrichmentStage;
import eu.intracherche.intracherche.indexing.pipeline.ParsingStage;
import eu.intracherche.intracherche.indexing.pipeline.PipelineContext;
import eu.intracherche.intracherche.indexing.model.IndexTask;
import eu.intracherche.intracherche.indexing.worker.DocumentCompletionTracker;
import eu.intracherche.intracherche.indexing.worker.IndexingProgressTracker;
import eu.intracherche.intracherche.queues.IndexTaskQueue;
import eu.intracherche.intracherche.queues.OcrQueue;
import eu.intracherche.intracherche.registry.FileRecord;
import eu.intracherche.intracherche.registry.FileRegistry;
import eu.intracherche.intracherche.selection.FairInterleavingPolicy;
import eu.intracherche.intracherche.selection.FileSelectionPolicy;
import jakarta.annotation.PreDestroy;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.request.CoreAdminRequest;
import org.apache.solr.client.solrj.response.CoreAdminResponse;
import org.apache.solr.common.util.NamedList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Service class that processes files from the FileRegistry for indexing.
 * This service reads files that have been discovered but not yet indexed,
 * and processes them through the indexing pipeline.
 */
@Service
public class FileIndexingService {
    private static final Logger log = LoggerFactory.getLogger(FileIndexingService.class);
    public final String INTRACHERCHE_CONFIG_SET = "intracherche_configset_v1";
    private final FileRegistry fileRegistry;
    private final IndexesConfig indexesConfig; // Injected directly
    private final SolrConfig solrConfig;

    private final SmbClientManager smbClientManager;

    private final SolrClient solrClient; // Final and injected
    private final ExecutorService executorService;
    private final DocumentParser parser;
    private final List<DocumentEnricher> sequentialEnrichers;
    private final List<DocumentEnricher> parallelEnrichers;
    private final NerService nerService;
    private final NerConfig nerConfig;
    private final EmbeddingService embeddingService;
    private final IndexTaskQueue indexTaskQueue;
    private final OcrQueue ocrQueue;
    private final DocumentCompletionTracker documentCompletionTracker;
    private final IndexingProgressTracker progressTracker;
    private final ServiceOutageMonitor outageMonitor;
    // Where the config set should be written into on Solr server
    String SOLR_CONFIG_SETS_REL_DIR = "server/solr/configsets";
    private final ConcurrentHashMap<String, Object> coreCreationLocks = new ConcurrentHashMap<>();
    private volatile boolean paused = false;

    // Consecutive SMB files that failed on the connection. Past the threshold the share is
    // treated as down and the cycle stops: every remaining file would fail the same way, and
    // each would be marked FAILED with an error row — thousands of them for a host that
    // merely slept (Sept. 2026). Twice the pool size, so the failures of one wave of
    // in-flight files on a connection that has just been rebuilt do not trip it.
    static final int SMB_OUTAGE_THRESHOLD = 8;
    private final java.util.concurrent.atomic.AtomicInteger consecutiveSmbFailures =
            new java.util.concurrent.atomic.AtomicInteger();


    // A file whose parsing took the application down this many times is indexed by its name only. With
    // several files parsed at once the first crash may be a bystander's: suspects are retried one at a
    // time, so a second crash is the file's own.
    static final int MAX_CRASHES = 2;
    private final java.util.concurrent.atomic.AtomicBoolean interruptedAttemptsRecovered =
            new java.util.concurrent.atomic.AtomicBoolean();

    // indexing-config.maxFileSizeMb / maxTextChars; see setIndexingLimits.
    private volatile long maxFileSizeBytes = IndexingConfig.DEFAULT_MAX_FILE_SIZE_MB * 1024 * 1024;
    private volatile long maxTextChars = IndexingConfig.DEFAULT_MAX_TEXT_CHARS;

    /**
     * The limits on what one file may cost. A file is read into memory whole before parsing,
     * with several files in flight at once, so a single oversized file could exhaust the heap
     * and stop indexing and search alike; and a small file can expand into a huge text. Past a
     * limit the file is not dropped: it is indexed by its name, or up to the text limit, and
     * listed in the admin panel. Raising a limit indexes those files again (see
     * ApplicationOrchestrator and FileRegistryDao.requeueLimitedFiles).
     */
    @Autowired(required = false)
    void setIndexingLimits(ApplicationConfig applicationConfig) {
        IndexingConfig cfg = applicationConfig.getIndexingConfig();
        if (cfg == null) return;
        this.maxFileSizeBytes = cfg.maxFileSizeBytes();
        this.maxTextChars = cfg.maxTextChars();
        log.info("Indexing limits: {} MB per file, {} characters of text per file",
                maxFileSizeBytes / (1024 * 1024), maxTextChars);
    }

    // Spring Boot will automatically find and inject solrClient bean
    public FileIndexingService(
            FileRegistry fileRegistry,
            IndexesConfig indexesConfig,
            SolrConfig solrSettings,
            SolrClient solrClient,
            SmbClientManager smbClientManager,
            NerService nerService,
            NerConfig nerConfig,
            EmbeddingService embeddingService,
            IndexTaskQueue indexTaskQueue,
            OcrQueue ocrQueue,
            DocumentCompletionTracker documentCompletionTracker,
            IndexingProgressTracker progressTracker,
            ServiceOutageMonitor outageMonitor
    ) {
        this.fileRegistry = fileRegistry;
        this.indexesConfig = indexesConfig;
        this.solrConfig = solrSettings;
        this.executorService = Executors.newFixedThreadPool(4);
        // No OcrService: scanned pages go through the OCR queue, and an index with OCR off
        // passes no queue so they are skipped. With a service, the parser fell back to OCRing
        // them synchronously on the indexing thread, which made `ocr: false` a no-op.
        this.parser = new TikaDocumentParser();
        this.nerService = nerService;
        this.nerConfig  = nerConfig;
        this.embeddingService = embeddingService;
        this.sequentialEnrichers = List.of(new LanguageDetectorEnricher());
        // While a service is down, the enrichers wait for it; an admin pause ends the wait.
        this.parallelEnrichers   = List.of(
                new LocationNerEnricher(nerService, nerConfig, outageMonitor, () -> paused),
                new VectorEnricher(embeddingService, outageMonitor, () -> paused));
        this.solrClient = solrClient;
        this.smbClientManager = smbClientManager;
        this.indexTaskQueue = indexTaskQueue;
        this.ocrQueue = ocrQueue;
        this.documentCompletionTracker = documentCompletionTracker;
        this.progressTracker = progressTracker;
        this.outageMonitor = outageMonitor;
    }

    public void pauseProcessing()  { paused = true; }
    public void resumeProcessing() { paused = false; }

    /**
     * Ensures that if you stop the Linux service, the thread pool shuts down cleanly
     */
    @PreDestroy
    public void tearDown() {
        log.info("Shutting down indexing thread pool...");
        executorService.shutdown();
        try {
            // Stopped on purpose: files still in flight are not the cause of a crash.
            if (!executorService.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS)) executorService.shutdownNow();
            fileRegistry.clearAllAttempts();
        } catch (Exception e) {
            log.warn("Could not clear the parsing markers at shutdown: {}", e.getMessage());
        }
    }

    /**
     * The files being parsed when the application last died. Without a clean shutdown (an
     * OutOfMemoryError exit, a kill, a power cut) their marker is still set: each gets one crash
     * counted against it.
     */
    private void recoverInterruptedAttempts() {
        if (!interruptedAttemptsRecovered.compareAndSet(false, true)) return;
        try {
            List<String> ids = fileRegistry.recoverInterruptedAttempts();
            if (ids != null && !ids.isEmpty()) {
                long heap = MemoryHints.suggestedHeapMb(heapMb(), totalRamMb());
                log.warn("The application stopped while parsing {} file(s); counting one crash against each. "
                        + "They are parsed one at a time from now on, and a file that crashes it again is "
                        + "indexed by its name only.{}", ids.size(),
                        heap > 0 ? " If this repeats, raise the heap (now " + heapMb() + " MB) to about " + heap
                                + " MB: IC_HEAP_MB in /etc/intracherche/jvm.conf." : "");
            }
        } catch (Exception e) {
            log.warn("Could not recover the files interrupted by the last crash: {}", e.getMessage());
        }
    }

    /**
     * True when every index a file belongs to is paused, so the file must wait.
     *
     * <p>Pausing an index stops its discovery in the orchestrator, but files discovered before
     * the pause are already pending in the registry, and this loop — run once per active index —
     * used to take them all whatever their index: a paused Wikipedia core kept growing by
     * thousands of pages (Sept. 2026). They stay pending rather than being skipped for good, so
     * the index picks up where it stopped once set back to ACTIVE.
     *
     * <p>A file shared with an active index is still indexed, into every index it belongs to:
     * holding it back would leave the active index without it for as long as the other is paused.
     */
    static boolean onlyInPausedCores(List<String> cores, Set<String> pausedCores) {
        return cores != null && !cores.isEmpty() && pausedCores.containsAll(cores);
    }

    /**
     * Process all files in the registry that need to be indexed.
     * This method reads files from the registry and processes them through the indexing pipeline.
     */
    public void processFilesToIndex() throws IOException, InterruptedException {
        log.info("Starting to process files for indexing...");
        recoverInterruptedAttempts();

        try {
            // Use a try-catch to handle potential SQLException from findFilesToIndex
            List<FileRecord> filesToIndex = fileRegistry.findFilesToIndex();
            Set<String> pausedCores = indexesConfig.getIndexes().stream()
                    .filter(idx -> idx.getStatus() == eu.intracherche.intracherche.config.Status.PAUSED)
                    .map(IndexConfig::getCoreName)
                    .collect(Collectors.toSet());
            Map<Boolean, List<FileRecord>> byPaused = filesToIndex.stream()
                    .collect(Collectors.partitioningBy(r -> onlyInPausedCores(r.indexMembershipActual(), pausedCores)));
            filesToIndex = byPaused.get(false);
            if (!byPaused.get(true).isEmpty()) {
                log.info("Leaving {} pending file(s) of paused index(es) {} for when they resume",
                        byPaused.get(true).size(), pausedCores);
            }
            log.info("Found {} files to index", filesToIndex.size());

            // Always reset the tracker — even with 0 files — so idle cycles
            // don't leave a stale 100% displayed on the admin panel.
            // Indexes marked for deletion are tombstones the admin has yet to remove from the
            // config: seeding them would give each one a row in the admin panel's cycle
            // summary, next to indexes that actually exist.
            List<String> indexNames = indexesConfig.getIndexes().stream()
                    .filter(idx -> idx.getStatus() != eu.intracherche.intracherche.config.Status.DELETE)
                    .map(IndexConfig::getCoreName)
                    .toList();
            progressTracker.startCycle(filesToIndex.size(), indexNames);

            if (filesToIndex.isEmpty()) {
                log.info("No files to index");
                return;
            }

            // Log details about files being selected for indexing
            for (FileRecord record : filesToIndex) {
                log.debug("File selected for indexing on server {} in share {}: {} (indexed_at: {}, mtime: {})",
                    record.smbHost(), record.smbShareName(), record.path(), record.indexedAt(), record.mtime());
            }

            // Use FairInterleavingPolicy to select files fairly across indexes
            FileSelectionPolicy selectionPolicy = new FairInterleavingPolicy();

            // Process files in batches while maintaining fair interleaving
            List<FileRecord> remainingFiles = new ArrayList<>(filesToIndex);
            int batchSize = 100; // Maintain the original batch size for performance
            consecutiveSmbFailures.set(0);

            // Files that already crashed the application go first, one at a time: if one crashes it again
            // it is the one to blame, which parsing them four at a time could not tell.
            Set<String> suspects = fileRegistry.crashSuspectIds();
            if (suspects != null && !suspects.isEmpty()) {
                List<FileRecord> solo = remainingFiles.stream().filter(r -> suspects.contains(r.id())).toList();
                remainingFiles.removeAll(solo);
                if (!solo.isEmpty()) log.info("Parsing {} file(s) that crashed the application one at a time", solo.size());
                for (FileRecord f : solo) {
                    if (paused || smbOutage()) break;
                    processBatch(List.of(f));
                }
            }

            while (!remainingFiles.isEmpty()) {
                if (paused) {
                    log.info("Indexing paused — stopping batch loop ({} files left unprocessed, will retry next run)", remainingFiles.size());
                    break;
                }
                if (smbOutage()) {
                    log.warn("{} SMB files in a row failed on the connection — share treated as down, stopping batch loop ({} files left pending, will retry next run)",
                            SMB_OUTAGE_THRESHOLD, remainingFiles.size());
                    break;
                }
                // Determine batch size (don't exceed remaining files)
                int currentBatchSize = Math.min(batchSize, remainingFiles.size());

                // Select files for this batch using fair interleaving
                List<FileRecord> batch = new ArrayList<>();
                for (int i = 0; i < currentBatchSize; i++) {
                    Optional<FileRecord> selectedFile = selectionPolicy.selectNext(remainingFiles);
                    if (selectedFile.isPresent()) {
                        batch.add(selectedFile.get());
                        remainingFiles.remove(selectedFile.get());
                    } else {
                        // No more files to process
                        break;
                    }
                }

                if (!batch.isEmpty()) {
                    log.info("Processing batch of {} files (fairly interleaved across indexes)", batch.size());
                    processBatch(batch);
                }
            }

            log.info("Finished processing files for indexing");
        } catch (Exception e) {
            log.error("Error processing files for indexing", e);
            throw new IOException("Failed to process files for indexing", e);
        }
    }


    /**
     * Process a single file through the indexing pipeline.
     * 
     * This method determines which Solr cores to index the file into based on 
     * the indexMembershipActual field, which contains the indexes the file should be in
     * based on its path configuration. The indexMembershipSolr field tracks which indexes
     * the file has already been indexed into.
     * 
     * Files are selected for indexing based on:
     * - If indexed_at IS NULL (never indexed)
     * - If indexed_at < mtime (file modified after last indexing)
     * 
     * This ensures that files are re-indexed when:
     * 1. They are new (never indexed)
     * 2. They have been modified since last indexing
     * 3. Their index membership has changed (indexMembershipActual vs indexMembershipSolr)
     */
    private void processFile(FileRecord fileRecord, ContentSource source) throws IndexingException, IOException {
        String pathString = fileRecord.path();
        Path virtualPath = Path.of(pathString);
        String firstCoreName = fileRecord.indexMembershipActual().isEmpty() ? "?"
                               : fileRecord.indexMembershipActual().get(0);

        boolean ocrQueued = false;
        try {
        log.debug("Processing file for page-level indexing: {}", pathString);

        // Stage 1: Parse — the source (and its SMB share) is closed as soon as the
        // stream is consumed.  Enrichment (e.g. per-page NER) runs afterwards so that
        // a long enrichment pass does not hold an idle SMB connection until the server's
        // socket timeout fires and drops it.
        org.apache.tika.metadata.Metadata parseMetadata = new org.apache.tika.metadata.Metadata();
        parseMetadata.set(org.apache.tika.metadata.TikaCoreProperties.RESOURCE_NAME_KEY,
                virtualPath.getFileName().toString());
        TikaDocumentParser tikaParser = (TikaDocumentParser) parser;

        // Too large to read into memory: indexed by its name only, never downloaded.
        ParseResult parseResult;
        String contentLimit = null;
        String limitMessage = null;
        int crashes = 0;
        try {
            crashes = fileRegistry.crashCount(fileRecord.id());
        } catch (Exception e) {
            log.warn("Could not read the crash count of {}: {}", pathString, e.getMessage());
        }
        boolean tooLarge = fileRecord.size() > maxFileSizeBytes;
        if (tooLarge || crashes >= MAX_CRASHES) {
            try { source.close(); } catch (Exception ignored) {}
            if (tooLarge) {
                contentLimit = "size:" + maxFileSizeBytes;
                limitMessage = String.format("Contenu non indexé : fichier de %d Mo, au-delà de la limite de %d Mo "
                                + "(indexing-config.maxFileSizeMb). Trouvable par son nom uniquement.",
                        fileRecord.size() / (1024 * 1024), maxFileSizeBytes / (1024 * 1024));
            } else {
                contentLimit = "crash:" + heapMb();
                limitMessage = String.format("Contenu non indexé : la lecture de ce fichier (%d Mo) a arrêté l'application "
                                + "%d fois, même seul (mémoire insuffisante). Trouvable par son nom uniquement. %s",
                        fileRecord.size() / (1024 * 1024), crashes,
                        MemoryHints.advice(pathString, fileRecord.size(), heapMb(), totalRamMb()));
            }
            log.warn("{} — {}", pathString, limitMessage);
            parseResult = new ParseResult(
                    List.of(new IndexedSection("Nom du fichier", virtualPath.getFileName().toString())),
                    parseMetadata, null, 0, 0, true);
        } else {
            // Download the raw bytes and release the SMB connection before any parsing.
            // PDFBox loading and page analysis happen entirely in memory but can take well
            // over the 10-second SMB socket idle timeout, which causes SMBJ to mark the
            // DiskShare as closed before source.close() is even called.
            byte[] rawBytes;
            try (InputStream is = source.getInputStream()) {
                rawBytes = is.readAllBytes();
            } catch (Exception e) {
                if (fileRecord.isSmb() && isTransientSmb(e)) {
                    // The connection broke mid-download: processBatch rebuilds it.
                    try { source.close(); } catch (Exception ignored) {}
                    throw new IOException(e.getMessage(), e);
                }
                log.error("Failed to read {}: {}", pathString, e.getMessage(), e);
                progressTracker.recordFailure(firstCoreName, pathString, "Lecture impossible: " + e.getMessage());
                try { source.close(); } catch (Exception ignored) {}
                return;
            }
            try {
                source.close();
            } catch (Exception e) {
                // Connection may have dropped during the download; the bytes are already in
                // memory so this is non-fatal.
                log.debug("SMB close error for {} (non-fatal, bytes already read): {}", pathString, e.getMessage());
            }
            // SMB session stays open for the duration of the cycle so parallel threads
            // sharing the same share don't close each other's connection mid-flight.
            // The orchestrator calls smbClientManager.closeSessions() after each cycle.

            try {
                String coreName = fileRecord.indexMembershipActual().get(0);
                OcrQueue effectiveOcrQueue = isOcrEnabledForCore(coreName) ? ocrQueue : null;
                if (effectiveOcrQueue != null) {
                    // Parsing queues the scanned pages for OCR, and an OCR worker writes each page to
                    // Solr as soon as it is read: long before this file's text is embedded and the core
                    // would otherwise be created. A page sent to a core that is not there is lost.
                    for (String core : fileRecord.indexMembershipActual()) {
                        if (!createCoreIfNotExists(core)) {
                            log.warn("Core '{}' unavailable; leaving file '{}' pending for the next cycle.", core, pathString);
                            progressTracker.recordFailure(core, pathString, "Création du core Solr impossible");
                            markFailedSilently(fileRecord, pathString);
                            return;
                        }
                    }
                }
                parseResult = tikaParser.parse(
                        rawBytes,
                        parseMetadata,
                        effectiveOcrQueue,
                        pathString,
                        fileRecord.id(),
                        coreName,
                        fileRecord.accessTokens(),
                        fileRecord.denyTokens(),
                        fileRecord.mtime(),
                        fileRecord.smbHost(),
                        fileRecord.smbShareName(),
                        effectiveOcrQueue != null ? documentCompletionTracker : null,
                        new TextBudget(maxTextChars)
                );
            } catch (UnparsableDocumentException e) {
                log.warn("Cannot read {} ({}): it is left alone until its size or modification date changes",
                        pathString, OcrService.rootCause(e));
                progressTracker.recordFailure(firstCoreName, pathString, "Parsing impossible: " + OcrService.rootCause(e));
                markParseFailedSilently(fileRecord, pathString);
                return;
            } catch (Exception e) {
                log.error("Failed to parse {}: {}", pathString, e.getMessage(), e);
                progressTracker.recordFailure(firstCoreName, pathString, "Parsing impossible: " + e.getMessage());
                markFailedSilently(fileRecord, pathString);
                return;
            }
            if (parseResult != null && parseResult.textLimited()) {
                contentLimit = "chars:" + maxTextChars;
                limitMessage = textLimitMessage(maxTextChars, parseResult.getSections().size());
                log.warn("{} — {}", pathString, limitMessage);
            }
        }

        if (parseResult == null) {
            log.warn("No content extracted for file (marking indexed to stop retrying): {}", pathString);
            progressTracker.recordSkipped(firstCoreName, pathString);
            markIndexedSilently(fileRecord, pathString);
            return;
        }
        if (parseResult.getSections().isEmpty()) {
            if (parseResult.hasPendingOcr()) {
                for (String coreName : fileRecord.indexMembershipActual()) {
                    if (!createCoreIfNotExists(coreName)) {
                        log.warn("Skipping core '{}' for scanned file '{}'.", coreName, pathString);
                    }
                }
                log.info("File {} fully scanned ({} pages enqueued for OCR); deferring registry update to OCR workers",
                        pathString, parseResult.ocrTasksEnqueued());
                ocrQueued = true;
                // Tracker already registered inside PdfPageExtractor before first enqueue.
            } else {
                log.warn("No content extracted for file (marking indexed to stop retrying): {}", pathString);
                progressTracker.recordSkipped(firstCoreName, pathString);
                markIndexedSilently(fileRecord, pathString);
            }
            return;
        }

        // Stage 2: Build document and enrich (NER, language detection, etc.)
        // Provide a text sample so LanguageDetectorEnricher can run (it only needs ~5 000 chars).
        String langSample = parseResult.getSections().stream()
                .map(IndexedSection::content)
                .filter(s -> s != null && !s.isBlank())
                .collect(java.util.stream.Collectors.joining("\n\n"));
        if (langSample.length() > 5_000) langSample = langSample.substring(0, 5_000);
        IndexedDocument indexedDocument = new IndexedDocument(
                fileRecord.id(),
                virtualPath,
                langSample.isBlank() ? null : langSample,
                parseResult.metadata(),
                fileRecord.accessTokens(),
                fileRecord.denyTokens(),
                null,
                fileRecord.mtime(),
                fileRecord.smbHost(),
                fileRecord.smbShareName()
        );
        PipelineContext context = new PipelineContext(virtualPath, null);
        context.setParseResult(parseResult);
        context.setIndexedDocument(indexedDocument);

        EnrichmentStage enrichmentStage = new EnrichmentStage(sequentialEnrichers, parallelEnrichers);
        enrichmentStage.execute(context);

        // Stage 3: Enqueue IndexTasks for async processing by IndexWorker pool.
        // Every core must be reachable before anything is sent. A file marked indexed while
        // absent from one of its cores (seen while Solr was stopping) dropped out of
        // findFilesToIndex() for good: it stays pending instead, and the next cycle retries.
        var coreNames = fileRecord.indexMembershipActual();
        for (String coreName : coreNames) {
            if (!createCoreIfNotExists(coreName)) {
                log.warn("Core '{}' unavailable; leaving file '{}' pending for the next cycle.", coreName, pathString);
                progressTracker.recordFailure(coreName, pathString, "Création du core Solr impossible");
                markFailedSilently(fileRecord, pathString);
                return;
            }
        }
        if (coreNames.isEmpty()) {
            markIndexedSilently(fileRecord, pathString);
            return;
        }

        // Registered before the first publish so IndexWorker cannot finish a write the
        // tracker does not know about yet. The tracker marks the file indexed once every
        // write is done; marking it here, at enqueue time, lost the file whenever a write
        // failed or the app stopped with tasks still queued.
        documentCompletionTracker.expectWrites(fileRecord.id(), coreNames.size());
        for (int i = 0; i < coreNames.size(); i++) {
            String coreName = coreNames.get(i);
            IndexTask task = IndexTask.fromPipeline(
                    fileRecord.id(),
                    coreName,
                    pathString,
                    parseResult.getSections(),
                    context.indexedDocument().metadata(),
                    fileRecord.accessTokens(),
                    fileRecord.denyTokens(),
                    fileRecord.mtime(),
                    fileRecord.smbHost(),
                    fileRecord.smbShareName(),
                    context.getSectionVectors()
            );
            try {
                indexTaskQueue.publish(task);
                log.debug("Enqueued IndexTask for document {} into core {}", fileRecord.id(), coreName);
            } catch (InterruptedException e) {
                log.error("Interrupted while enqueuing IndexTask for document {}: {}", fileRecord.id(), e.getMessage());
                // Settle the writes that will never happen, as failures: the file stays pending.
                for (int j = i; j < coreNames.size(); j++) {
                    documentCompletionTracker.solrPageDone(fileRecord.id(), false, false);
                }
                Thread.currentThread().interrupt();
                throw new IndexingException("Interrupted while enqueuing IndexTask for document: " + fileRecord.id(), e);
            }
        }

        // Stage 4: Record progress (the registry is updated once Solr has the file)
        for (String cn : coreNames) {
            progressTracker.recordSuccess(cn, pathString);
        }
        // After recordSuccess, which clears the file's rows in the admin panel list.
        if (limitMessage != null) progressTracker.recordLimited(firstCoreName, pathString, limitMessage);
        try {
            fileRegistry.setContentLimit(fileRecord.id(), contentLimit);
        } catch (Exception e) {
            log.warn("Could not record the content limit of {}: {}", pathString, e.getMessage());
        }
        progressTracker.recordTextPages(parseResult.textPageCount(), firstCoreName);
        log.info("File enqueued for Solr ({} sections): {}", parseResult.getSections().size(), pathString);
        } finally {
            if (!ocrQueued) {
                progressTracker.fileCompleted(pathString);
            }
        }
    }

    private static long heapMb() {
        return Runtime.getRuntime().maxMemory() / (1024L * 1024L);
    }

    private static long totalRamMb() {
        try {
            return ((com.sun.management.OperatingSystemMXBean) java.lang.management.ManagementFactory
                    .getOperatingSystemMXBean()).getTotalMemorySize() / (1024L * 1024L);
        } catch (Exception | LinkageError e) {
            return -1;
        }
    }

    /** Sets or clears the marker that tells the next start this file was being parsed. */
    private void markAttempt(FileRecord fileRecord, boolean started) {
        try {
            if (started) fileRegistry.markAttemptStarted(fileRecord.id());
            else fileRegistry.clearAttempt(fileRecord.id());
        } catch (Exception e) {
            log.warn("Could not {} the parsing marker of {}: {}", started ? "set" : "clear", fileRecord.path(), e.getMessage());
        }
    }

    private void markIndexedSilently(FileRecord fileRecord, String pathString) {
        try {
            fileRegistry.markIndexed(fileRecord.id());
        } catch (Exception e) {
            log.warn("Failed to mark empty file as indexed: {}", pathString, e);
        }
    }

    private void markParseFailedSilently(FileRecord fileRecord, String pathString) {
        try {
            fileRegistry.markParseFailed(fileRecord.id());
        } catch (Exception e) {
            log.warn("Failed to mark file as unparsable in registry: {}", pathString, e);
        }
    }

    private void markFailedSilently(FileRecord fileRecord, String pathString) {
        try {
            fileRegistry.markFailed(fileRecord.id());
        } catch (Exception e) {
            log.warn("Failed to mark file as failed in registry: {}", pathString, e);
        }
    }

    /**
     * Process a batch of files through the indexing pipeline.
     */
    private void processBatch(List<FileRecord> batch) throws InterruptedException {
        List<java.util.concurrent.Future<?>> futures = new ArrayList<>(batch.size());

        for (FileRecord fileRecord : batch) {
            futures.add(executorService.submit(() -> {
                // The share is down: leave the file pending, untouched, for the next cycle.
                if (smbOutage()) return;
                // A service indexing needs is down: the file is not read before it is back,
                // and is left untouched if the admin pauses meanwhile.
                if (outageMonitor != null && !outageMonitor.awaitAll(() -> paused)) return;
                progressTracker.startFile(fileRecord.path(), fileRecord.indexMembershipActual());
                DiskShare share = null;
                markAttempt(fileRecord, true);
                try {
                    ContentSource source;
                    if (fileRecord.isSmb()) {
                        // The host matters: the same share name may exist on another server.
                        share = smbClientManager.getShare(fileRecord.smbHost(), fileRecord.smbShareName());
                        source = new SmbContentSource(share, fileRecord.path());
                    } else {
                        source = new LocalContentSource(Path.of(fileRecord.path()));
                    }
                    processFile(fileRecord, source);
                    consecutiveSmbFailures.set(0);
                } catch (Throwable e) {
                    ServiceUnavailableException outage = findCause(e, ServiceUnavailableException.class);
                    if (outage != null) {
                        // Stopped waiting for a service (admin pause): not the file's failure.
                        log.info("Stopped waiting for the {} service: '{}' left pending for the next cycle",
                                outage.dependency().id, fileRecord.path());
                        progressTracker.fileCompleted(fileRecord.path());
                        return;
                    }
                    if (findCause(e, OutOfMemoryError.class) != null) {
                        // The application survived this time; the next one may not.
                        try {
                            fileRegistry.recordCrash(fileRecord.id());
                        } catch (Exception ex) {
                            log.warn("Could not record the memory exhaustion of '{}': {}", fileRecord.path(), ex.getMessage());
                        }
                    }
                    String msg = e.getMessage();
                    boolean isTransientSmb = fileRecord.isSmb() && isTransientSmb(e);
                    if (isTransientSmb) {
                        log.warn("Transient SMB error for '{}' (will retry next cycle): {}", fileRecord.path(), msg);
                        consecutiveSmbFailures.incrementAndGet();
                        if (share != null) {
                            smbClientManager.reportBrokenShare(fileRecord.smbHost(), fileRecord.smbShareName(), share);
                        }
                    } else {
                        log.error("Failed to index file '{}': {}", fileRecord.path(), e.getMessage(), e);
                    }
                    String coreName = fileRecord.indexMembershipActual().isEmpty() ? "?"
                                      : fileRecord.indexMembershipActual().get(0);
                    progressTracker.recordFailure(coreName, fileRecord.path(), msg != null ? msg : e.getClass().getSimpleName());
                    markFailedSilently(fileRecord, fileRecord.path());
                    progressTracker.fileCompleted(fileRecord.path());
                } finally {
                    markAttempt(fileRecord, false);
                }
            }));
        }

        for (java.util.concurrent.Future<?> f : futures) {
            try {
                f.get();
            } catch (java.util.concurrent.ExecutionException e) {
                log.error("Unexpected error from parallel indexing task: {}", e.getCause().getMessage(), e.getCause());
            }
        }
    }

    private boolean smbOutage() {
        return consecutiveSmbFailures.get() >= SMB_OUTAGE_THRESHOLD;
    }

    /**
     * A failure of the connection rather than of the file: broken pipe, timeout, a share smbj
     * already closed, or no SMB credits left (the server stopped answering, so smbj may send
     * nothing more on that connection). The file itself is fine and is retried next cycle.
     */
    static boolean isTransientSmb(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof com.hierynomus.protocol.transport.TransportException) return true;
            String msg = t.getMessage();
            if (msg != null && (msg.contains("TimeoutException") || msg.contains("already been closed")
                    || msg.contains("Not enough credits"))) {
                return true;
            }
        }
        return false;
    }

    /** The first throwable of {@code type} in {@code e}'s cause chain, or null. */
    static <T extends Throwable> T findCause(Throwable e, Class<T> type) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (type.isInstance(t)) return type.cast(t);
        }
        return null;
    }

    /**
     * Check if a Solr core exists.
     *
     * @param coreName the name of the core to check
     * @return true if the core exists, false otherwise
     */
    private boolean isOcrEnabledForCore(String coreName) {
        return indexesConfig.getIndexes().stream()
                .filter(idx -> idx.getCoreName().equalsIgnoreCase(coreName))
                .findFirst()
                .map(eu.intracherche.intracherche.config.IndexConfig::isOcr)
                .orElse(true);
    }

    private boolean coreExists(String coreName) {
        try {
            // Request status for the specific core
            CoreAdminResponse response = CoreAdminRequest.getStatus(coreName, solrClient);

            // Get the status list for all requested cores
            NamedList<Object> coreStatus = response.getCoreStatus(coreName);

            // If the core doesn't exist, the Map for that name will be empty (size 0)
            return response.getCoreStatus(coreName).size() > 0;
        } catch (SolrServerException | IOException e) {
            log.warn("Error checking if core '{}' exists: {}", coreName, e.getMessage());
            return false;
        }
    }

    /**
     * Create a Solr core if it doesn't exist.
     *
     * @param coreName the name of the core to create
     * @return true if core was created or already existed, false if creation failed
     */
    public boolean createCoreIfNotExists(String coreName) {
        Object lock = coreCreationLocks.computeIfAbsent(coreName, k -> new Object());
        synchronized (lock) {
            try {
                // First check if core exists
                if (coreExists(coreName)) {
                    log.debug("Core '{}' already exists", coreName);
                    return true;
                }

                // Create the core
                log.info("Creating Solr core: {}", coreName);
                CoreAdminRequest.Create createRequest = new CoreAdminRequest.Create();
                createRequest.setCoreName(coreName);

                // We use a custom configset
                // Use a configuration set that exists in the Solr installation
                ensureConfigSetExists(INTRACHERCHE_CONFIG_SET);

                // Verify that all config set files are present before creating the core
                if (!areAllConfigSetFilesPresent(INTRACHERCHE_CONFIG_SET)) {
                    log.warn("Config set files are not properly present for core '{}'. Cannot create core.", coreName);
                    return false;
                }

                createRequest.setConfigSet(INTRACHERCHE_CONFIG_SET);

                // Set the instance directory to a location where Solr can find the config files
                // We'll use a path that will work with the local Solr installation
                createRequest.setInstanceDir(coreName);

                CoreAdminResponse response = createRequest.process(solrClient);

                if (response.getStatus() == 0) {
                    log.info("Successfully created Solr core: {}", coreName);
                    return true;
                } else {
                    log.warn("Failed to create Solr core '{}': status code {}", coreName, response.getStatus());
                    return false;
                }
            } catch (SolrServerException | IOException e) {
                log.error("Error creating Solr core '{}': {}", coreName, e.getMessage(), e);
                return false;
            }
        }
    }

    /**
     * Delete a Solr core if it exists.
     *
     * @param coreName the name of the core to delete
     * @return true if core was deleted or didn't exist, false if deletion failed
     */
    public boolean deleteCoreIfExists(String coreName) {
        try {
            // First check if core exists
            if (!coreExists(coreName)) {
                log.debug("Core '{}' does not exist, skipping deletion", coreName);
                return true;
            }

            // Delete the core
            log.info("Deleting Solr core: {}", coreName);
            // Use the static factory method unloadCore
            // deleteIndex: true removes the data directory
            // deleteInstanceDir: true removes the config/conf directory
            CoreAdminResponse response = CoreAdminRequest.unloadCore(coreName, true, true, solrClient);

            if (response.getStatus() == 0) {
                log.info("Successfully deleted Solr core: {}", coreName);
                return true;
            } else {
                log.warn("Failed to delete Solr core '{}': status code {}", coreName, response.getStatus());
                return false;
            }
        } catch (SolrServerException | IOException e) {
            log.error("Error deleting Solr core '{}': {}", coreName, e.getMessage(), e);
            return false;
        }
    }


    /**
     * Check that all files present in resources/solr/configset are also present in solrRootPath + "server/solr/configsets" + configSetName.
     *
     * @param configSetName the name of the configset to check
     * @return true if all files are present, false otherwise
     */
    public boolean areAllConfigSetFilesPresent(String configSetName) {
        try {
            // Get the source configset directory from resources
            Path sourceConfigSetPath = Paths.get(solrConfig.configSetSourcePath());

            // Check if source directory exists
            if (!Files.exists(sourceConfigSetPath)) {
                log.warn("Source configset directory does not exist: {}", sourceConfigSetPath);
                return false;
            }

            // Check if target directory exists in Solr
            String solrConfigSetsPath = Paths.get("solr", SOLR_CONFIG_SETS_REL_DIR).toString();
            Path targetConfigSetPath = Paths.get(solrConfigSetsPath, configSetName, "conf");

            if (!Files.exists(targetConfigSetPath)) {
                log.warn("Target configset directory does not exist in Solr: {}", targetConfigSetPath);
                return false;
            }

            // Get list of all files in source directory
            List<Path> sourceFiles = new ArrayList<>();
            Files.walk(sourceConfigSetPath)
                    .filter(Files::isRegularFile)
                    .forEach(sourceFiles::add);

            // Check each file exists in target directory
            for (Path sourceFile : sourceFiles) {
                // Get relative path from source configset root
                Path relativePath = sourceConfigSetPath.relativize(sourceFile);
                Path targetFile = targetConfigSetPath.resolve(relativePath);


                if (!Files.exists(targetFile)) {
                    log.warn("Missing file in Solr configset: {}", targetFile);
                    return false;
                }
            }

            log.info("All files in {} are present in Solr configset dir: {}", sourceConfigSetPath, targetConfigSetPath);
            return true;

        } catch (Exception e) {
            log.error("Error checking configset files: {}", e.getMessage(), e);
            return false;
        }
    }

    public void ensureConfigSetExists(String configSetName) {
        try {
            // Check if configset already exists in Solr configsets directory
            String solrConfigSetsPath = Paths.get("solr", SOLR_CONFIG_SETS_REL_DIR).toString();
            Path targetConfigSetPath = Paths.get(solrConfigSetsPath, configSetName, "conf");
            // Get the source configset directory from resources
            Path sourceConfigSetPath = Paths.get(solrConfig.configSetSourcePath());

            // Instead of returning, we delete if it exists to prepare for a fresh copy
            if (Files.exists(targetConfigSetPath)) {
                log.info("ConfigSet '{}' exists. Overwriting with latest configuration...", configSetName);
                deleteDirectory(targetConfigSetPath);
            } else {
                log.info("ConfigSet '{}' does not exist. Creating new...", configSetName);
            }


            // Ensure parent directory exists
            Files.createDirectories(targetConfigSetPath.getParent());


            // Copy the entire directory structure
            copyDirectory(sourceConfigSetPath, targetConfigSetPath);

            log.info("Successfully created ConfigSet '{}' in Solr at {}", configSetName, targetConfigSetPath);

        } catch (Exception e) {
            log.error("Error ensuring ConfigSet '{}' exists: {} - {}", configSetName, e.getMessage(), e);
            throw new RuntimeException("Failed to ensure configset exists: " + configSetName, e);
        }
    }

    /**
     * Recursively delete a directory and all its contents.
     */
    private void deleteDirectory(Path path) throws IOException {
        if (Files.exists(path)) {
            Files.walk(path)
                    .sorted(java.util.Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(java.io.File::delete);
        }
    }

    /**
     * Recursively copy a directory and all its contents.
     */
    private void copyDirectory(Path source, Path target) throws IOException {
        if (!Files.exists(target)) {
            Files.createDirectories(target);
        }

        Files.walk(source)
                .forEach(sourcePath -> {
                    try {
                        Path targetPath = target.resolve(source.relativize(sourcePath));
                        if (Files.isDirectory(sourcePath)) {
                            if (!Files.exists(targetPath)) {
                                Files.createDirectories(targetPath);
                            }
                        } else {
                            Files.copy(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING);
                        }
                    } catch (IOException e) {
                        throw new RuntimeException("Failed to copy file: " + sourcePath, e);
                    }
                });
    }

    /**
     * Shutdown the service and release resources.
     */
    public void shutdown() {
        log.info("Shutting down indexing service...");
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(30, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }

        // DON'T SHUT IT DOWN AFTER INDEXING SINCE WE
        // NEED IT FOR SEARCHING!!!
        log.info("Indexing service shutdown complete");
    }

    /**
     * The admin-facing text when a file hit {@code indexing-config.maxTextChars}: what was kept,
     * what was lost, and the setting to raise.
     */
    static String textLimitMessage(long maxTextChars, int keptSections) {
        return String.format("Contenu indexé en partie : seuls les %d premiers caractères (%d sections) sont "
                + "trouvables, la suite du fichier ne l'est pas. Pour l'indexer en entier, relevez "
                + "indexing-config.maxTextChars dans /etc/intracherche/application.yaml (voir INSTALL.md, "
                + "« Limites par fichier » : la mémoire de l'application doit suivre).", maxTextChars, keptSections);
    }
}
