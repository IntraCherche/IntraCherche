package eu.intracherche.intracherche.services;

import eu.intracherche.intracherche.app.ApplicationOrchestrator;
import eu.intracherche.intracherche.config.ApplicationConfig;
import eu.intracherche.intracherche.config.NerConfig;
import eu.intracherche.intracherche.indexing.enrichment.LanguageDetectorEnricher;
import eu.intracherche.intracherche.indexing.enrichment.LocationNerEnricher;
import eu.intracherche.intracherche.indexing.enrichment.VectorEnricher;
import eu.intracherche.intracherche.indexing.enrichment.DocumentEnricher;
import eu.intracherche.intracherche.indexing.model.IndexedDocument;
import eu.intracherche.intracherche.indexing.parser.IndexedSection;
import eu.intracherche.intracherche.indexing.parser.ParseResult;
import eu.intracherche.intracherche.indexing.parser.PdfPageExtractor;
import eu.intracherche.intracherche.indexing.pipeline.PipelineContext;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class BenchmarkService {

    private static final Logger log = LoggerFactory.getLogger(BenchmarkService.class);

    public enum Phase { IDLE, PREPARING_SCANNED, RUNNING, DONE, FAILED }
    public enum BenchmarkType { TEXT, SCANNED }

    public record BenchmarkResult(
            String type,
            int pages,
            long extractionMs,
            long languageMs,
            long nerMs,
            long embeddingMs,
            long enrichmentMs,
            long totalMs,
            double pagesPerMinute,
            String completedAt
    ) {}

    private final OcrService ocrService;
    private final ApplicationOrchestrator orchestrator;
    private final List<DocumentEnricher> sequentialEnrichers;
    private final List<DocumentEnricher> parallelEnrichers;
    private final Path scannedPdfPath;

    private final ExecutorService runner = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "benchmark-runner");
        t.setDaemon(true);
        return t;
    });

    private final AtomicReference<Phase> phase = new AtomicReference<>(Phase.IDLE);
    private volatile BenchmarkType currentType;
    private volatile Instant startedAt;
    private volatile BenchmarkResult lastResult;
    private volatile String errorMessage;
    private volatile boolean wasStopped;
    private final AtomicInteger benchmarkTotalPages = new AtomicInteger(0);
    private final AtomicInteger benchmarkPagesDone = new AtomicInteger(0);
    private volatile java.util.concurrent.Future<?> currentTask;

    public BenchmarkService(OcrService ocrService,
                            NerService nerService,
                            NerConfig nerConfig,
                            EmbeddingService embeddingService,
                            ApplicationOrchestrator orchestrator,
                            ApplicationConfig applicationConfig) {
        this.ocrService = ocrService;
        this.orchestrator = orchestrator;
        // Kept beside the registry database, i.e. in the data directory
        // (/var/lib/intracherche in a packaged install, db/ in a dev checkout).
        this.scannedPdfPath = Path.of(applicationConfig.getRegistryConfig().getDbPath())
                .toAbsolutePath().resolveSibling("benchmark-scanned.pdf");
        this.sequentialEnrichers = List.of(new LanguageDetectorEnricher());
        this.parallelEnrichers   = List.of(
                new LocationNerEnricher(nerService, nerConfig),
                new VectorEnricher(embeddingService)
        );
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Start a benchmark run asynchronously.
     * Returns false if the indexer is not paused or a run is already in progress.
     */
    public boolean start(BenchmarkType type) {
        if (!orchestrator.isPaused()) return false;
        if (!phase.compareAndSet(Phase.IDLE, Phase.RUNNING) &&
            !phase.compareAndSet(Phase.DONE, Phase.RUNNING)) return false;
        currentType  = type;
        startedAt            = Instant.now();
        errorMessage         = null;
        wasStopped           = false;
        benchmarkTotalPages.set(0);
        benchmarkPagesDone.set(0);
        currentTask = runner.submit(() -> {
            try {
                lastResult = doRun(type);
                // The enrichment phase (CompletableFuture.allOf) cannot be interrupted, so doRun()
                // may complete even after stop() was called. If it did complete, the result is valid:
                // clear wasStopped and always show DONE.
                wasStopped = false;
                phase.set(Phase.DONE);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.info("Benchmark interrompu.");
                phase.compareAndSet(Phase.RUNNING, Phase.IDLE);
            } catch (Exception e) {
                if (Thread.currentThread().isInterrupted() || e.getCause() instanceof InterruptedException) {
                    log.info("Benchmark interrompu.");
                    phase.compareAndSet(Phase.RUNNING, Phase.IDLE);
                } else {
                    log.error("Benchmark failed: {}", e.getMessage(), e);
                    errorMessage = e.getMessage();
                    phase.compareAndSet(Phase.RUNNING, Phase.FAILED);
                }
            }
        });
        return true;
    }

    /**
     * Interrupt a running benchmark or scanned-PDF preparation.
     * Returns false if nothing is running.
     */
    public boolean stop() {
        Phase current = phase.get();
        if (current != Phase.RUNNING && current != Phase.PREPARING_SCANNED) return false;
        java.util.concurrent.Future<?> task = currentTask;
        if (task != null) task.cancel(true);
        phase.set(Phase.IDLE);
        errorMessage = null;
        wasStopped   = true;
        log.info("Benchmark arrêté par l'utilisateur (phase précédente : {}).", current);
        return true;
    }

    /**
     * Generate the image-only (scanned) PDF from the bundled text PDF.
     * Stores it in the data directory, beside the registry database. Idempotent: skips if the file exists.
     * Returns false if another operation is already running.
     */
    public boolean prepareScanned() {
        if (!phase.compareAndSet(Phase.IDLE, Phase.PREPARING_SCANNED)) return false;
        errorMessage = null;
        currentTask = runner.submit(() -> {
            try {
                doGenerateScannedPdf();
                phase.set(Phase.IDLE);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.info("Génération du PDF scanné interrompue — suppression du fichier partiel.");
                tryDeletePartialScannedPdf();
                phase.set(Phase.IDLE);
            } catch (Exception e) {
                if (Thread.currentThread().isInterrupted() || e.getCause() instanceof InterruptedException) {
                    log.info("Génération du PDF scanné interrompue — suppression du fichier partiel.");
                    tryDeletePartialScannedPdf();
                    phase.set(Phase.IDLE);
                } else {
                    log.error("Failed to generate scanned PDF: {}", e.getMessage(), e);
                    errorMessage = e.getMessage();
                    phase.set(Phase.FAILED);
                }
            }
        });
        return true;
    }

    public void resetFailure() {
        if (!phase.compareAndSet(Phase.FAILED, Phase.IDLE)) {
            phase.compareAndSet(Phase.DONE, Phase.IDLE);
        }
        errorMessage = null;
        wasStopped   = false;
    }

    public Map<String, Object> getStatus() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("phase",          phase.get().name());
        m.put("type",           currentType != null ? currentType.name() : null);
        m.put("startedAt",      startedAt   != null ? startedAt.toString() : null);
        m.put("scannedPdfReady", Files.exists(scannedPdfPath));
        m.put("lastResult",     lastResult);
        m.put("errorMessage",   errorMessage);
        m.put("wasStopped",     wasStopped);
        m.put("pagesDone",      benchmarkPagesDone.get());
        m.put("pagesTotal",     benchmarkTotalPages.get());
        return m;
    }

    // ── Pipeline ──────────────────────────────────────────────────────────────

    private BenchmarkResult doRun(BenchmarkType type) throws Exception {
        log.info("Benchmark [{}]: démarrage", type);

        byte[] pdfBytes = loadPdf(type);

        // Stage 1: extraction (PDF parsing, or PDF rendering + OCR for scanned)
        // benchmarkTotalPages is set by extractPages right after the PDF is loaded,
        // before the page loop — so the frontend progress bar gets the total early.
        log.info("Benchmark [{}]: extraction…", type);
        long t0 = System.currentTimeMillis();
        List<IndexedSection> sections = (type == BenchmarkType.TEXT)
                ? PdfPageExtractor.extractPages(pdfBytes, "benchmark-text.pdf", null, benchmarkPagesDone, benchmarkTotalPages)
                : PdfPageExtractor.extractPages(pdfBytes, "benchmark-scanned.pdf", ocrService, benchmarkPagesDone, benchmarkTotalPages);
        long extractionMs = System.currentTimeMillis() - t0;

        if (sections.isEmpty()) throw new IllegalStateException("Aucune section extraite du PDF.");
        log.info("Benchmark [{}]: {} sections en {}ms", type, sections.size(), extractionMs);

        // Stage 2: enrichment — step by step with individual timing
        log.info("Benchmark [{}]: enrichissement…", type);
        ParseResult parseResult = new ParseResult(sections, new org.apache.tika.metadata.Metadata(), "application/pdf", 0, sections.size());
        String content = sections.stream().map(IndexedSection::content).collect(java.util.stream.Collectors.joining("\n\n"));
        IndexedDocument doc = new IndexedDocument("benchmark", Path.of("benchmark.pdf"),
                content, new org.apache.tika.metadata.Metadata(), Set.of(), Set.of(), null, null, null, null);
        PipelineContext ctx = new PipelineContext(Path.of("benchmark.pdf"), null);
        ctx.setParseResult(parseResult);
        ctx.setIndexedDocument(doc);

        // Stage 2a: sequential enrichers (language detection)
        long langStart = System.currentTimeMillis();
        for (DocumentEnricher enricher : sequentialEnrichers) {
            enricher.enrich(ctx);
        }
        long languageMs = System.currentTimeMillis() - langStart;
        log.info("Benchmark [{}]: langue en {}ms", type, languageMs);

        // Stage 2b: parallel enrichers (NER + embedding) with per-enricher wall-clock timing
        var stepTimings = new ConcurrentHashMap<String, Long>();
        var vt = Executors.newVirtualThreadPerTaskExecutor();
        @SuppressWarnings("unchecked")
        CompletableFuture<Void>[] futures = parallelEnrichers.stream()
                .map(enricher -> CompletableFuture.runAsync(() -> {
                    long t = System.currentTimeMillis();
                    try { enricher.enrich(ctx); }
                    catch (java.io.IOException e) { throw new CompletionException(e); }
                    stepTimings.put(enricher.getClass().getSimpleName(), System.currentTimeMillis() - t);
                }, vt))
                .toArray(CompletableFuture[]::new);
        try {
            CompletableFuture.allOf(futures).join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause();
            throw new Exception("Parallel enrichment failed: " +
                    (cause != null ? cause.getMessage() : e.getMessage()), cause != null ? cause : e);
        }
        long nerMs       = stepTimings.getOrDefault("LocationNerEnricher", 0L);
        long embeddingMs = stepTimings.getOrDefault("VectorEnricher", 0L);
        long parallelMs  = Math.max(nerMs, embeddingMs); // actual wall-clock for the parallel phase
        long enrichmentMs = languageMs + parallelMs;
        log.info("Benchmark [{}]: enrichissement en {}ms (langue={}ms, NER={}ms, embedding={}ms, parallèle={}ms)",
                type, enrichmentMs, languageMs, nerMs, embeddingMs, parallelMs);

        long totalMs = extractionMs + enrichmentMs;
        double pagesPerMinute = sections.size() / (totalMs / 60_000.0);

        log.info("""
                Benchmark [{}] ✓ {} pages/min
                  Pages traitées             {}
                  Extraction (PDFBox)        {}
                  Détection de langue        {}
                  NER (entités nommées)      {} ↳ parallèle
                  Vectorisation (embedding)  {} ↳ parallèle
                  Total                      {}""",
                type, String.format("%.1f", pagesPerMinute), sections.size(),
                formatMs(extractionMs), formatMs(languageMs),
                formatMs(nerMs), formatMs(embeddingMs), formatMs(totalMs));

        return new BenchmarkResult(
                type.name(),
                sections.size(),
                extractionMs,
                languageMs,
                nerMs,
                embeddingMs,
                enrichmentMs,
                totalMs,
                pagesPerMinute,
                Instant.now().toString()
        );
    }

    // ── Scanned PDF generation ────────────────────────────────────────────────

    private void doGenerateScannedPdf() throws Exception {
        Path output = scannedPdfPath;
        if (Files.exists(output)) {
            log.info("PDF scanné déjà présent: {}", output);
            return;
        }
        Files.createDirectories(output.getParent());

        log.info("Génération du PDF scanné (rendu à 150 DPI, JPEG 85%)…");
        ClassPathResource src = new ClassPathResource("benchmark/benchmark-text.pdf");
        byte[] textBytes = src.getInputStream().readAllBytes();

        try (PDDocument source = Loader.loadPDF(textBytes);
             PDDocument scanned = new PDDocument()) {
            PDFRenderer renderer = new PDFRenderer(source);
            int total = source.getNumberOfPages();
            for (int i = 0; i < total; i++) {
                if (i % 10 == 0) log.info("  Rendu page {}/{}", i + 1, total);
                BufferedImage img = renderer.renderImageWithDPI(i, 150f);
                PDPage page = new PDPage(new PDRectangle(img.getWidth(), img.getHeight()));
                scanned.addPage(page);
                PDImageXObject pdImg = JPEGFactory.createFromImage(scanned, img, 0.85f);
                try (PDPageContentStream cs = new PDPageContentStream(scanned, page)) {
                    cs.drawImage(pdImg, 0, 0, img.getWidth(), img.getHeight());
                }
            }
            scanned.save(output.toFile());
        }
        log.info("PDF scanné enregistré : {} ({} Ko)", output, Files.size(output) / 1024);
    }

    private byte[] loadPdf(BenchmarkType type) throws Exception {
        if (type == BenchmarkType.TEXT) {
            return new ClassPathResource("benchmark/benchmark-text.pdf").getInputStream().readAllBytes();
        }
        Path p = scannedPdfPath;
        if (!Files.exists(p)) throw new IllegalStateException(
                "PDF scanné non disponible. Cliquez sur « Préparer le PDF scanné » d'abord.");
        return Files.readAllBytes(p);
    }

    private void tryDeletePartialScannedPdf() {
        try { Files.deleteIfExists(scannedPdfPath); } catch (Exception ignored) {}
    }

    private static String formatMs(long ms) {
        if (ms < 1_000)  return ms + "ms";
        if (ms < 60_000) return String.format("%.1fs", ms / 1000.0);
        return (ms / 60_000) + "m " + (ms % 60_000 / 1000) + "s";
    }
}
