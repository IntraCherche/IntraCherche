package eu.intracherche.intracherche.web;

import eu.intracherche.intracherche.app.ApplicationOrchestrator;
import eu.intracherche.intracherche.audit.AuditService;
import eu.intracherche.intracherche.config.*;
import eu.intracherche.intracherche.indexing.error.IndexingErrorDao;
import eu.intracherche.intracherche.indexing.outage.ServiceOutageMonitor;
import eu.intracherche.intracherche.indexing.worker.IndexingProgressTracker;
import eu.intracherche.intracherche.indexing.worker.IndexWorkerManager;
import eu.intracherche.intracherche.process.PythonServiceManager;
import eu.intracherche.intracherche.process.ResourceValidator;
import eu.intracherche.intracherche.process.SolrProcessManager;
import eu.intracherche.intracherche.registry.FileRegistry;
import eu.intracherche.intracherche.services.EmbeddingService;
import eu.intracherche.intracherche.services.FileIndexingService;
import eu.intracherche.intracherche.services.NerService;
import eu.intracherche.intracherche.services.OcrService;
import eu.intracherche.intracherche.services.RerankerService;
import eu.intracherche.intracherche.sherlock.LlmService;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.solr.client.solrj.SolrRequest;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.request.CoreAdminRequest;
import org.apache.solr.client.solrj.response.CoreAdminResponse;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.params.CoreAdminParams;
import org.apache.solr.common.util.NamedList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);

    private final IndexingProgressTracker progressTracker;
    private final IndexWorkerManager indexWorkerManager;
    private final FileRegistry fileRegistry;
    private final AdminConfig adminConfig;
    private final ApplicationOrchestrator orchestrator;
    private final FileIndexingService fileIndexingService;
    private final ServiceOutageMonitor outageMonitor;

    // Python services — already have isHealthy() + URL config
    private final EmbeddingService embeddingService;
    private final NerService nerService;
    private final OcrService ocrService;
    private final RerankerService rerankerService;
    private final EmbeddingConfig embeddingConfig;
    private final NerConfig nerConfig;
    private final OcrConfig ocrConfig;
    private final RerankerConfig rerankerConfig;

    // Ollama is external (systemd-managed) — status is reported but not controllable here
    private final LlmService llmService;
    private final LlmConfig llmConfig;

    private final SolrClient solrClient;
    private final SolrConfig solrConfig;
    private final IndexesConfig indexesConfig;
    private final PythonServiceManager pythonServiceManager;
    private final SolrProcessManager solrProcessManager;
    private final ResourceValidator resourceValidator;
    private final AuditService auditService;

    @Value("${app.version}")
    private String appVersion;

    public AdminController(IndexingProgressTracker progressTracker,
                           IndexWorkerManager indexWorkerManager,
                           FileRegistry fileRegistry,
                           AdminConfig adminConfig,
                           ApplicationOrchestrator orchestrator,
                           EmbeddingService embeddingService,
                           NerService nerService,
                           OcrService ocrService,
                           RerankerService rerankerService,
                           EmbeddingConfig embeddingConfig,
                           NerConfig nerConfig,
                           OcrConfig ocrConfig,
                           RerankerConfig rerankerConfig,
                           LlmService llmService,
                           LlmConfig llmConfig,
                           SolrClient solrClient,
                           SolrConfig solrConfig,
                           IndexesConfig indexesConfig,
                           PythonServiceManager pythonServiceManager,
                           SolrProcessManager solrProcessManager,
                           ResourceValidator resourceValidator,
                           FileIndexingService fileIndexingService,
                           AuditService auditService,
                           ServiceOutageMonitor outageMonitor) {
        this.progressTracker = progressTracker;
        this.indexWorkerManager = indexWorkerManager;
        this.fileRegistry = fileRegistry;
        this.adminConfig = adminConfig;
        this.orchestrator = orchestrator;
        this.embeddingService = embeddingService;
        this.nerService = nerService;
        this.ocrService = ocrService;
        this.rerankerService = rerankerService;
        this.embeddingConfig = embeddingConfig;
        this.nerConfig = nerConfig;
        this.ocrConfig = ocrConfig;
        this.rerankerConfig = rerankerConfig;
        this.llmService = llmService;
        this.llmConfig = llmConfig;
        this.solrClient = solrClient;
        this.solrConfig = solrConfig;
        this.indexesConfig = indexesConfig;
        this.pythonServiceManager = pythonServiceManager;
        this.solrProcessManager = solrProcessManager;
        this.resourceValidator = resourceValidator;
        this.fileIndexingService = fileIndexingService;
        this.outageMonitor = outageMonitor;
        this.auditService = auditService;
    }

    // ── Indexer status & progress ─────────────────────────────────────────────

    @GetMapping("/indexer/status")
    public ResponseEntity<?> getIndexerStatus() {
        if (!isAdminAuthorized()) return forbidden();
        try {
            IndexingProgressTracker.ProgressSnapshot progress = progressTracker.getSnapshot();
            long total = fileRegistry.countAllFiles();
            long pending = fileRegistry.countFilesToIndex();

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("isRunning",             progress.running());
            body.put("isPaused",              orchestrator.isPaused());
            body.put("waitingFor",            waitingFor());
            body.put("cycleTotal",            progress.cycleTotal());
            body.put("cycleProcessed",        progress.cycleProcessed());
            body.put("textPagesTotal",        progress.textPagesTotal());
            body.put("ocrPagesTotal",         progress.ocrPagesTotal());
            body.put("cycleTextPages",        progress.cycleTextPages());
            body.put("cycleOcrPages",         progress.cycleOcrPages());
            body.put("cycleOcrActiveMs",      progress.cycleOcrActiveMs());
            body.put("appStartedAt",          nullableString(progress.appStartedAt()));
            body.put("cycleStartedAt",        nullableString(progress.cycleStartedAt()));
            body.put("lastCycleCompletedAt",  nullableString(progress.lastCycleCompletedAt()));
            body.put("activeFiles",            buildActiveFilesList(progress.activeFiles()));
            body.put("lastFileCompletedAt",   nullableString(progress.lastFileCompletedAt()));
            body.put("queueDepth",            indexWorkerManager.getQueueSize());
            body.put("registry",              Map.of("total", total, "pending", pending,
                                                     "orphans", fileRegistry.countOrphanFiles()));
            body.put("lastRound",             lastRoundBody(progress.lastRound()));
            return ResponseEntity.ok(body);
        } catch (Exception e) {
            return error("Failed to retrieve indexer status: " + e.getMessage());
        }
    }

    /** The last round that processed files, or null before the first one. */
    private static Map<String, Object> lastRoundBody(IndexingProgressTracker.LastRound round) {
        if (round == null) return null;
        Map<String, Object> indexes = new LinkedHashMap<>();
        round.indexes().forEach((name, s) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("succeeded",  s.succeeded());
            m.put("failed",     s.failed());
            m.put("skipped",    s.skipped());
            m.put("textPages",  s.textPages());
            m.put("ocrPages",   s.ocrPages());
            m.put("durationMs", s.durationMs());
            m.put("ocrActiveMs", s.ocrActiveMs());
            indexes.put(name, m);
        });
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("completedAt", round.completedAt().toString());
        body.put("durationMs",  round.durationMs());
        body.put("files",       round.files());
        body.put("textPages",   round.textPages());
        body.put("ocrPages",    round.ocrPages());
        body.put("ocrActiveMs", round.ocrActiveMs());
        body.put("indexes",     indexes);
        return body;
    }

    /** The services indexing is waiting for, each with when its outage was noticed. */
    private List<Map<String, Object>> waitingFor() {
        List<Map<String, Object>> list = new ArrayList<>();
        outageMonitor.outages().forEach((dependency, since) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("service", dependency.id);
            m.put("label",   dependency.label);
            m.put("since",   since.toString());
            list.add(m);
        });
        return list;
    }

    // ── Pause / resume ────────────────────────────────────────────────────────

    @PostMapping("/indexer/pause")
    public ResponseEntity<?> pause() {
        if (!isAdminAuthorized()) return forbidden();
        orchestrator.pause();
        return ResponseEntity.ok(Map.of("paused", true));
    }

    @PostMapping("/indexer/resume")
    public ResponseEntity<?> resume() {
        if (!isAdminAuthorized()) return forbidden();
        orchestrator.resume();
        return ResponseEntity.ok(Map.of("paused", false));
    }

    // ── Indexing errors ───────────────────────────────────────────────────────

    @GetMapping("/indexer/errors")
    public ResponseEntity<?> getErrors() {
        if (!isAdminAuthorized()) return forbidden();
        List<IndexingErrorDao.Row> rows = progressTracker.getErrorRows();
        List<Map<String, Object>> result = new ArrayList<>(rows.size());
        for (IndexingErrorDao.Row r : rows) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("kind", r.kind());
            entry.put("timestamp", r.timestamp().toString());
            entry.put("path", r.path());
            entry.put("message", "skipped".equals(r.kind()) ? "Aucun contenu extractible" : r.message());
            entry.put("coreName", r.coreName());
            result.add(entry);
        }
        return ResponseEntity.ok(result);
    }

    // ── Service health ────────────────────────────────────────────────────────

    @GetMapping("/health")
    public ResponseEntity<?> getHealth() {
        if (!isAdminAuthorized()) return forbidden();

        Map<String, Object> services = new LinkedHashMap<>();
        services.put("embedding", serviceHealth("embedding", embeddingConfig.getPythonUrl(),
                embeddingConfig.isEnabled(), embeddingService.isHealthy()));
        services.put("ner",       serviceHealth("ner",       nerConfig.getPythonUrl(),
                true, nerService.isHealthy()));
        if (ocrConfig.usesNode()) {
            // The node's OCR first; the "ocr" row below is then this server's fallback.
            OcrService.Health node = ocrService.nodeHealth();
            Map<String, Object> ocrGpu = serviceHealth("ocrGpu", ocrConfig.getGpuUrl(), true, node != null);
            if (node != null && node.device() != null) {
                ocrGpu.put("device", node.device());
                ocrGpu.put("problems", node.onGpu() ? List.of() : List.of("OCR_NOT_ON_GPU"));
            }
            services.put("ocrGpu", ocrGpu);
        }
        services.put("ocr",       serviceHealth("ocr",       ocrConfig.getPythonUrl(),
                true, ocrService.isHealthy()));
        if (rerankerConfig.isUsed() && rerankerConfig.usesNode()) {
            // As for OCR: the node's reranker first; the "reranker" row is then this server's fallback.
            Map<String, Object> node = serviceHealth("rerankerGpu", rerankerConfig.getGpuUrl(),
                    true, rerankerService.isNodeHealthy());
            if (Boolean.TRUE.equals(node.get("up"))) putDeployment(node, rerankerService.nodeDeployment());
            services.put("rerankerGpu", node);
        }
        Map<String, Object> reranker = serviceHealth("reranker",  rerankerConfig.getPythonUrl(),
                rerankerConfig.isUsed(), rerankerService.isHealthy());
        if (rerankerConfig.isUsed() && Boolean.TRUE.equals(reranker.get("up"))) {
            // Where it actually runs, so a GPU install that fell back to the processor shows here.
            putDeployment(reranker, rerankerService.localDeployment());
        }
        services.put("reranker",  reranker);
        if (llmConfig.isEnabled() && llmConfig.usesNode()) {
            Map<String, Object> node = serviceHealth("llmGpu", llmConfig.getGpuUrl(), true, llmService.isNodeHealthy());
            if (Boolean.TRUE.equals(node.get("up"))) {
                putPlacement(node, llmService.nodePlacement(), llmConfig.resolvedGpuModel(), true);
            }
            services.put("llmGpu", node);
        }
        Map<String, Object> llm = serviceHealth("llm",       llmConfig.getOllamaUrl(),
                llmConfig.isEnabled(), llmService.isLocalHealthy());
        boolean ollamaElsewhere = llmConfig.ollamaElsewhere();
        // Which machine to go and start it on, when it does not answer.
        llm.put("remote", ollamaElsewhere);
        if (llmConfig.isEnabled() && Boolean.TRUE.equals(llm.get("up"))) {
            putPlacement(llm, llmService.placement(), llmConfig.getModel(), ollamaElsewhere);
        }
        services.put("llm",       llm);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("services", services);
        body.put("solr", getSolrHealth());
        return ResponseEntity.ok(body);
    }

    // ── CPU / RAM allocation ──────────────────────────────────────────────────

    @GetMapping("/resources")
    public ResponseEntity<?> getResources() {
        if (!isAdminAuthorized()) return forbidden();
        ResourceValidator.AllocationResult alloc = resourceValidator.getLastAllocation();
        if (alloc == null) return ResponseEntity.ok(Map.of());

        List<Map<String, Object>> services = new ArrayList<>();
        for (var s : alloc.services()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key",     s.key());
            m.put("name",    s.name());
            m.put("workers", s.workers());
            m.put("ramMb",   s.ramMb());
            m.put("auto",    s.auto());
            services.add(m);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("totalCores",    alloc.totalCores());
        body.put("reservedCores", alloc.reservedCores());
        body.put("totalRamMb",    alloc.totalRamMb());
        body.put("budgetRamMb",   alloc.budgetRamMb());
        body.put("reservedRamMb", alloc.reservedRamMb());
        body.put("heapRamMb",     alloc.heapRamMb());
        body.put("llmRamMb",      alloc.llmRamMb());
        body.put("services",      services);
        return ResponseEntity.ok(body);
    }

    // ── Service restart ───────────────────────────────────────────────────────

    @PostMapping("/services/{name}/restart")
    public ResponseEntity<?> restartService(@PathVariable String name) {
        if (!isAdminAuthorized()) return forbidden();
        try {
            if ("solr".equals(name)) {
                solrProcessManager.restart();
            } else {
                pythonServiceManager.restart(name);
            }
            return ResponseEntity.ok(Map.of("service", name, "restarting", true));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ── Version ───────────────────────────────────────────────────────────────

    @GetMapping("/version")
    public ResponseEntity<?> getVersion() {
        if (!isAdminAuthorized()) return forbidden();
        return ResponseEntity.ok(Map.of("version", appVersion));
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private Map<String, Object> serviceHealth(String name, String url, boolean enabled, boolean up) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("url", url);
        m.put("enabled", enabled);
        m.put("up", enabled && up);
        m.put("managed", pythonServiceManager.isManaged(name));
        m.put("restarting", pythonServiceManager.isRestarting(name));
        return m;
    }

    /** Where a reranker actually runs, so a GPU install that fell back to the processor shows. */
    private static void putDeployment(Map<String, Object> row, RerankerService.Deployment deployment) {
        row.put("device", deployment.device());
        row.put("maxPassages", deployment.maxPassages());
        row.put("candidates", deployment.candidates());
        row.put("problems", deployment.problems());
    }

    /** Where an Ollama holds the model now; nothing when it has unloaded it between questions. */
    private static void putPlacement(Map<String, Object> row, LlmService.ModelPlacement placement,
                                     String model, boolean ollamaElsewhere) {
        if (placement == null || !placement.loaded()) return;
        row.put("device", placement.sizeVram() == 0 ? "cpu" : "cuda");
        row.put("gpuPercent", placement.gpuPercent());
        row.put("model", model);
        row.put("problems", placement.offGpu(ollamaElsewhere) ? List.of("MODEL_OFF_GPU") : List.of());
    }

    private Map<String, Object> getSolrHealth() {
        Map<String, Object> solrInfo = new LinkedHashMap<>();
        solrInfo.put("url", solrConfig.solrBaseUrl());
        solrInfo.put("managed", solrProcessManager.isManaged());
        solrInfo.put("restarting", solrProcessManager.isRestarting());
        try {
            CoreAdminRequest req = new CoreAdminRequest();
            req.setAction(CoreAdminParams.CoreAdminAction.STATUS);
            CoreAdminResponse resp = req.process(solrClient);

            // Every configured index gets a row, in the order indexes.yaml declares them,
            // whether or not Solr holds a core for it. A core is created on the first
            // document indexed (FileIndexingService.createCoreIfNotExists), so keying this
            // table on Solr's core list alone made a freshly installed server — nothing
            // indexed yet, or a share it has never been able to reach — show no index at
            // all, and the panel then looked as if it had lost the feature.
            Map<String, Map<String, Object>> rows = new LinkedHashMap<>();
            if (indexesConfig.getIndexes() != null) {
                for (IndexConfig index : indexesConfig.getIndexes()) {
                    // An index on its way out (the orchestrator deletes core and registry
                    // rows on the next cycle) is left out; one merely marked DELETE without
                    // confirmDelete still holds its files and still counts.
                    if (index.getCoreName() == null) continue;
                    if (Status.DELETE.equals(index.getStatus()) && index.isConfirmDelete()) continue;
                    Map<String, Object> row = emptyCoreRow(index.getCoreName());
                    row.put("visibleTo", visibleTo(index));
                    rows.put(index.getCoreName(), row);
                }
            }

            resp.getCoreStatus().forEach((coreName, statusObj) -> {
                Map<String, Object> coreInfo = rows.computeIfAbsent(coreName, AdminController::emptyCoreRow);
                coreInfo.put("created", true);
                try {
                    @SuppressWarnings("unchecked")
                    NamedList<Object> status = (NamedList<Object>) statusObj;
                    @SuppressWarnings("unchecked")
                    NamedList<Object> index = (NamedList<Object>) status.get("index");
                    if (index != null) {
                        coreInfo.put("sizeInBytes", index.get("sizeInBytes"));
                        coreInfo.put("lastModified", index.get("lastModified"));
                    }
                    coreInfo.put("numFiles", countDistinctFiles(coreName));
                } catch (Exception ignored) {}
            });

            solrInfo.put("up", true);
            solrInfo.put("cores", new ArrayList<>(rows.values()));
        } catch (Exception e) {
            solrInfo.put("up", false);
            solrInfo.put("error", e.getMessage());
        }
        return solrInfo;
    }

    /**
     * Who sees the index, as the panel shows it: {@code all: true}, or the groups as AD spells
     * them, each with its SID. Absent from the row of a core indexes.yaml does not declare,
     * which nobody sees.
     */
    private static Map<String, Object> visibleTo(IndexConfig index) {
        Map<String, Object> visibleTo = new LinkedHashMap<>();
        visibleTo.put("all", index.isVisibleToAll());
        List<Map<String, String>> groups = new ArrayList<>();
        if (index.getVisibleToGroupSids() != null) {
            index.getVisibleToGroupSids().forEach((name, sid) -> groups.add(Map.of("name", name, "sid", sid)));
        }
        visibleTo.put("groups", groups);
        return visibleTo;
    }

    /**
     * A row for an index Solr holds no core for: nothing has been indexed into it yet,
     * so it holds no file and has no size and no date. {@code created} tells the panel
     * the difference between that and a core that exists and happens to be empty.
     */
    private static Map<String, Object> emptyCoreRow(String coreName) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", coreName);
        row.put("numFiles", 0);
        row.put("sizeInBytes", null);
        row.put("lastModified", null);
        row.put("created", false);
        return row;
    }

    /**
     * Number of distinct source files in a core.
     *
     * Solr's own numDocs counts index entries — one per PDF page or spreadsheet
     * sheet — so a 117-page report reads as 117. That figure matches nothing the
     * product shows anywhere else: search groups on parent_id and reports files
     * (see SearchController's group.field), so it is not exposed at all. Every
     * entry carries the registry file id in parent_id, so the bucket count on
     * that field is the file count the panel means. limit:0 returns numBuckets
     * without materialising the buckets themselves.
     *
     * Returns null if the count cannot be obtained; the panel then shows "—"
     * rather than a wrong number, and the section count is displayed regardless.
     */
    private Integer countDistinctFiles(String coreName) {
        try {
            SolrQuery q = new SolrQuery("*:*");
            q.setRows(0);
            q.set("json.facet", "{files:{type:terms,field:parent_id,limit:0,numBuckets:true}}");
            QueryResponse resp = solrClient.query(coreName, q, SolrRequest.METHOD.POST);

            // An empty core returns no facet block at all.
            if (resp.getResults() != null && resp.getResults().getNumFound() == 0) return 0;

            if (resp.getResponse().get("facets") instanceof NamedList<?> facets
                    && facets.get("files") instanceof NamedList<?> files
                    && files.get("numBuckets") instanceof Number n) {
                return n.intValue();
            }
            return null;
        } catch (Exception e) {
            log.debug("Could not count distinct files for core '{}': {}", coreName, e.getMessage());
            return null;
        }
    }

    // ── Re-index all ─────────────────────────────────────────────────────────

    /**
     * The phrase an admin has to type, normalised (see {@link #normalizeConfirmation}).
     * The panel shows "Oui je veux tout réindexer"; accents, case and punctuation are
     * forgiven, the words are not.
     */
    private static final String REINDEX_CONFIRMATION = "ouijeveuxtoutreindexer";

    /** How long to wait for a cycle that starts between the check and the pause. */
    private static final long REINDEX_QUIESCE_TIMEOUT_MS = 30_000;

    public record ReindexAllRequest(String confirmation) {}

    /**
     * Marks every file for re-indexing after pushing the current configset and reloading
     * the cores — the only way a schema or pipeline change reaches documents already
     * indexed, since the registry re-indexes on {@code indexed_at < mtime} and a schema
     * change moves no file's mtime.
     *
     * It is the most expensive thing the product can be asked to do: every file is
     * downloaded again, parsed again, OCR'd again, and embedded again, so it costs what
     * the first indexing cost. Three guards keep it from being run by accident or by
     * reflex:
     *
     * 1. the caller must send the confirmation phrase in the body, so neither a bare
     *    POST nor a page left open on the button can trigger it;
     * 2. it is refused while a cycle is running — the configset is deleted and copied
     *    back on disk, and a core created or reloaded during that window can fail to
     *    load, which would take search down rather than merely waste time;
     * 3. the indexer is paused for the duration and restored to the state it was in,
     *    closing the gap between the check above and the copy.
     */
    @PostMapping("/reindex-all")
    public ResponseEntity<?> reindexAll(@RequestBody(required = false) ReindexAllRequest body,
                                        HttpServletRequest request) {
        if (!isAdminAuthorized()) return forbidden();

        String username = currentUsername();
        if (!REINDEX_CONFIRMATION.equals(normalizeConfirmation(body == null ? null : body.confirmation()))) {
            log.warn("Full re-index refused for '{}': confirmation phrase missing or incorrect", username);
            return ResponseEntity.badRequest().body(Map.of("error",
                    "Ré-indexage complet non lancé : la phrase de confirmation n'a pas été saisie correctement."));
        }
        if (progressTracker.getSnapshot().running()) {
            return ResponseEntity.status(409).body(Map.of("error",
                    "Un cycle d'indexation est en cours. Relancez une fois le cycle terminé."));
        }

        boolean wasPaused = orchestrator.isPaused();
        if (!wasPaused) orchestrator.pause();
        try {
            if (!awaitIndexerQuiescent()) {
                return ResponseEntity.status(409).body(Map.of("error",
                        "Un cycle d'indexation a démarré entre-temps. Relancez une fois le cycle terminé."));
            }

            // 1. Push the latest configset (schema changes) to Solr's configsets directory.
            fileIndexingService.ensureConfigSetExists(fileIndexingService.INTRACHERCHE_CONFIG_SET);

            // 2. Reload every existing core so they pick up the new schema immediately.
            CoreAdminRequest statusReq = new CoreAdminRequest();
            statusReq.setAction(CoreAdminParams.CoreAdminAction.STATUS);
            CoreAdminResponse statusRes = statusReq.process(solrClient);
            int reloaded = 0;
            for (Map.Entry<String, ?> entry : statusRes.getCoreStatus()) {
                String coreName = entry.getKey();
                CoreAdminRequest reloadReq = new CoreAdminRequest();
                reloadReq.setAction(CoreAdminParams.CoreAdminAction.RELOAD);
                reloadReq.setCoreName(coreName);
                reloadReq.process(solrClient);
                log.info("Reloaded Solr core '{}' with updated schema", coreName);
                reloaded++;
            }

            // 3. Mark every file for re-indexing.
            int count = fileRegistry.getDao().resetAllIndexedAt();
            log.info("Admin triggered full re-index: {} cores reloaded, {} files marked for re-indexing", reloaded, count);
            auditService.logAdminAction(request, username, "REINDEX_ALL",
                    reloaded + " core(s) reloaded, " + count + " file(s) marked for re-indexing");
            return ResponseEntity.ok(Map.of("coresReloaded", reloaded, "markedForReindex", count));
        } catch (Exception e) {
            log.error("Failed to trigger full re-index", e);
            return error("Échec du ré-indexage complet : " + e.getMessage());
        } finally {
            if (!wasPaused) orchestrator.resume();
        }
    }

    /**
     * Waits for the running cycle to stop after {@code pause()}, which only takes effect
     * at the next batch boundary. Returns false if it is still running when the wait runs
     * out, in which case the caller must not touch the configset.
     */
    private boolean awaitIndexerQuiescent() {
        long deadline = System.currentTimeMillis() + REINDEX_QUIESCE_TIMEOUT_MS;
        while (progressTracker.getSnapshot().running()) {
            if (System.currentTimeMillis() >= deadline) return false;
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    /**
     * Keeps only the unaccented letters, so the phrase can be typed the way anyone would
     * write it — "Oui, je veux tout ré-indexer !" passes, and so does the same sentence
     * without accents or with a stray space. The words themselves still have to be
     * there, which is what makes the action deliberate rather than a reflex.
     */
    private static String normalizeConfirmation(String typed) {
        if (typed == null) return "";
        String withoutAccents = Normalizer.normalize(typed, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return withoutAccents.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
    }

    private String currentUsername() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null ? auth.getName() : "unknown";
    }

    private boolean isAdminAuthorized() {
        return adminConfig.isAdmin(SecurityContextHolder.getContext().getAuthentication());
    }

    private ResponseEntity<Map<String, Object>> forbidden() {
        auditService.logSecurityEvent("ACCESS_DENIED", "administration");
        return ResponseEntity.status(403)
                .body(Map.of("error", "Vous n'êtes pas autorisé(e) à accéder au panneau d'administration."));
    }

    private ResponseEntity<Map<String, Object>> error(String message) {
        return ResponseEntity.internalServerError().body(Map.of("error", message));
    }

    private List<Map<String, String>> buildActiveFilesList(java.util.Map<String, java.time.Instant> activeFiles) {
        List<Map<String, String>> result = new ArrayList<>();
        for (var entry : activeFiles.entrySet()) {
            Map<String, String> item = new LinkedHashMap<>();
            item.put("path", entry.getKey());
            item.put("startedAt", entry.getValue().toString());
            result.add(item);
        }
        return result;
    }

    private String nullableString(Object o) {
        return o != null ? o.toString() : null;
    }
}
