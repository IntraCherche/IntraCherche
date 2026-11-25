package eu.intracherche.intracherche.indexing.worker;

import eu.intracherche.intracherche.indexing.error.IndexingErrorDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory tracker for the progress of the current indexing cycle and recent errors.
 * Thread-safe: written by the indexing thread pool, read by the admin API.
 */
@Component
public class IndexingProgressTracker {

    private static final Logger log = LoggerFactory.getLogger(IndexingProgressTracker.class);
    private static final int MAX_CYCLE_FILES = 2000; // cap per-cycle lists to bound memory
    private static final String SEP = "----------------------------------------------------------------";

    private final IndexingErrorDao errorDao;
    private final IndexingRoundDao roundDao;
    private final Clock clock;

    @Autowired
    public IndexingProgressTracker(IndexingErrorDao errorDao, IndexingRoundDao roundDao) {
        this(errorDao, roundDao, Clock.systemUTC());
    }

    IndexingProgressTracker(IndexingErrorDao errorDao, IndexingRoundDao roundDao, Clock clock) {
        this.errorDao = errorDao;
        this.roundDao = roundDao;
        this.clock = clock;
        this.appStartedAt = Instant.now(clock);
        this.ocrBusy = new BusyClock(clock);
        this.lastRound = roundDao.load().orElse(null);
    }

    private final Instant appStartedAt;
    private final AtomicLong cycleTotal     = new AtomicLong(0);
    private final AtomicLong cycleProcessed = new AtomicLong(0);
    // Cumulative across all cycles since startup — never reset
    private final AtomicLong textPagesTotal = new AtomicLong(0);
    private final AtomicLong ocrPagesTotal  = new AtomicLong(0);
    // Per-cycle counters — reset at startCycle(), frozen at endCycle()
    private final AtomicLong cycleTextPages   = new AtomicLong(0);
    private final AtomicLong cycleOcrPages    = new AtomicLong(0);
    // Wall-clock time during which at least one page was being OCRed: several OCR workers
    // reading pages at the same time count once, so pages/min is the server's rate.
    private final BusyClock ocrBusy;
    private final ConcurrentHashMap<String, BusyClock> ocrBusyPerIndex = new ConcurrentHashMap<>();
    private volatile long lastCycleTextPages        = 0;
    private volatile long lastCycleOcrPages         = 0;
    private volatile long lastCycleOcrActiveMs      = 0;
    private volatile long lastCycleTotalMs          = 0;
    private volatile long lastCycleFilesProcessed   = 0;
    private final AtomicBoolean running     = new AtomicBoolean(false);
    private volatile Instant cycleStartedAt;
    private volatile Instant lastCycleCompletedAt;
    private volatile Instant lastFileCompletedAt;
    // Set by startCycle(), cleared by endCycle(): an endCycle() with no cycle open (the
    // file lookup failed before startCycle()) must not count the previous cycle twice.
    private boolean cycleOpen;

    // A round is one pass of the orchestrator over every source directory. It runs one
    // cycle per directory, and every cycle after the one that picked up the new files
    // usually finds nothing to do — so the panel reports rounds, not cycles, or an idle
    // cycle would replace the figures of the one that did the work.
    private long roundDurationMs;
    private long roundFiles;
    private long roundTextPages;
    private long roundOcrPages;
    private long roundOcrActiveMs;
    private final Map<String, IndexCycleSummary> roundIndexes = new TreeMap<>();
    // The last round that processed files — survives idle rounds and restarts.
    private volatile LastRound lastRound;

    // Tracks files currently being processed in parallel: path → start time
    private final ConcurrentHashMap<String, Instant> activeFiles = new ConcurrentHashMap<>();
    // The indexes of each file in activeFiles, to time each index on its own
    private final ConcurrentHashMap<String, List<String>> activeFileIndexes = new ConcurrentHashMap<>();
    // Per index, this cycle: {first file started, last file completed} in epoch ms. Indexes
    // processed one after the other each get their own time; files of several indexes
    // interleaved in one cycle give each index the time it shared with the others.
    private final ConcurrentHashMap<String, long[]> spanPerIndex = new ConcurrentHashMap<>();

    // Per-index counters for the current cycle
    private final ConcurrentHashMap<String, AtomicInteger> succeededPerIndex   = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> failedPerIndex      = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> skippedPerIndex     = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong>    textPagesPerIndex   = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong>    ocrPagesPerIndex    = new ConcurrentHashMap<>();

    // Per-cycle file lists — cleared at startCycle(), used only by logCycleSummary()
    // Separate from the DB-persisted ring buffer used by the admin API.
    private final Queue<IndexingError> currentCycleFailures = new ConcurrentLinkedQueue<>();
    private final Queue<SkippedFile>   currentCycleSkipped  = new ConcurrentLinkedQueue<>();

    public void startCycle(long total, List<String> indexNames) {
        cycleTotal.set(total);
        cycleProcessed.set(0);
        cycleTextPages.set(0);
        cycleOcrPages.set(0);
        ocrBusy.reset();
        ocrBusyPerIndex.values().forEach(BusyClock::reset);
        cycleStartedAt = Instant.now(clock);
        activeFiles.clear();
        activeFileIndexes.clear();
        spanPerIndex.clear();
        synchronized (this) { cycleOpen = true; }
        running.set(true);
        succeededPerIndex.clear();
        failedPerIndex.clear();
        skippedPerIndex.clear();
        textPagesPerIndex.clear();
        ocrPagesPerIndex.clear();
        for (String name : indexNames) {
            succeededPerIndex.put(name, new AtomicInteger(0));
            failedPerIndex.put(name, new AtomicInteger(0));
            skippedPerIndex.put(name, new AtomicInteger(0));
            textPagesPerIndex.put(name, new AtomicLong(0));
            ocrPagesPerIndex.put(name, new AtomicLong(0));
        }
        currentCycleFailures.clear();
        currentCycleSkipped.clear();
    }

    public void startFile(String path, List<String> indexNames) {
        Instant now = Instant.now(clock);
        activeFiles.put(path, now);
        activeFileIndexes.put(path, List.copyOf(indexNames));
        for (String index : indexNames) {
            spanPerIndex.compute(index, (k, span) -> {
                if (span == null) return new long[] { now.toEpochMilli(), 0 };
                span[0] = Math.min(span[0], now.toEpochMilli());
                return span;
            });
        }
    }

    public void fileCompleted(String path) {
        if (activeFiles.remove(path) != null) {
            cycleProcessed.incrementAndGet();
            Instant now = Instant.now(clock);
            lastFileCompletedAt = now;
            List<String> indexNames = activeFileIndexes.remove(path);
            if (indexNames == null) return;
            for (String index : indexNames) {
                spanPerIndex.computeIfPresent(index, (k, span) -> {
                    span[1] = Math.max(span[1], now.toEpochMilli());
                    return span;
                });
            }
        }
    }

    public void recordTextPages(int n, String coreName) {
        if (n > 0) {
            textPagesTotal.addAndGet(n);
            cycleTextPages.addAndGet(n);
            textPagesPerIndex.computeIfAbsent(coreName, k -> new AtomicLong()).addAndGet(n);
        }
    }

    public void recordOcrPage(String coreName) {
        ocrPagesTotal.incrementAndGet();
        cycleOcrPages.incrementAndGet();
        ocrPagesPerIndex.computeIfAbsent(coreName, k -> new AtomicLong()).incrementAndGet();
    }

    /** A page of a document of this index is sent to the OCR service. */
    public void ocrPageStarted(String coreName) {
        ocrBusy.start();
        ocrBusyPerIndex.computeIfAbsent(coreName, k -> new BusyClock(clock)).start();
    }

    /** The OCR service has answered for that page, or failed. */
    public void ocrPageFinished(String coreName) {
        ocrBusy.stop();
        BusyClock perIndex = ocrBusyPerIndex.get(coreName);
        if (perIndex != null) perIndex.stop();
    }

    /** Time during which at least one OCR request was in flight, since the last reset. */
    private static final class BusyClock {
        private final Clock clock;
        private int inFlight;
        private long since;
        private long busyMs;

        BusyClock(Clock clock) { this.clock = clock; }

        synchronized void start() {
            if (inFlight++ == 0) since = clock.millis();
        }

        synchronized void stop() {
            if (inFlight == 0) return;
            if (--inFlight == 0) busyMs += clock.millis() - since;
        }

        /** A request still in flight counts from now on. */
        synchronized void reset() {
            busyMs = 0;
            since = clock.millis();
        }

        synchronized long busyMs() {
            return busyMs + (inFlight > 0 ? clock.millis() - since : 0);
        }
    }

    public void endCycle() {
        Instant now = Instant.now(clock);
        lastCycleTextPages       = cycleTextPages.get();
        lastCycleOcrPages        = cycleOcrPages.get();
        lastCycleOcrActiveMs     = ocrBusy.busyMs();
        lastCycleTotalMs         = cycleStartedAt != null ? now.toEpochMilli() - cycleStartedAt.toEpochMilli() : 0;
        lastCycleFilesProcessed  = cycleProcessed.get();
        running.set(false);
        activeFiles.clear();
        lastCycleCompletedAt = now;
        addCycleToRound();
        logCycleSummary();
    }

    /** Starts a round: the orchestrator's pass over every source directory. */
    public synchronized void startRound() {
        roundDurationMs = 0;
        roundFiles = 0;
        roundTextPages = 0;
        roundOcrPages = 0;
        roundOcrActiveMs = 0;
        roundIndexes.clear();
    }

    /** Ends a round; one that processed files becomes the last round shown by the admin panel. */
    public synchronized void endRound() {
        if (roundFiles == 0) return;
        LastRound round = new LastRound(Instant.now(clock), roundDurationMs, roundFiles,
                roundTextPages, roundOcrPages, roundOcrActiveMs, new LinkedHashMap<>(roundIndexes));
        lastRound = round;
        roundDao.save(round);
    }

    private synchronized void addCycleToRound() {
        if (!cycleOpen) return;
        cycleOpen = false;
        if (lastCycleFilesProcessed == 0) return;
        roundDurationMs  += lastCycleTotalMs;
        roundFiles       += lastCycleFilesProcessed;
        roundTextPages   += lastCycleTextPages;
        roundOcrPages    += lastCycleOcrPages;
        roundOcrActiveMs += lastCycleOcrActiveMs;
        getIndexSummaries().forEach((index, s) -> roundIndexes.merge(index, s, (a, b) -> new IndexCycleSummary(
                a.succeeded() + b.succeeded(), a.failed() + b.failed(), a.skipped() + b.skipped(),
                a.textPages() + b.textPages(), a.ocrPages() + b.ocrPages(),
                a.durationMs() + b.durationMs(), a.ocrActiveMs() + b.ocrActiveMs())));
    }

    private void logCycleSummary() {
        Set<String> allIndexes = new HashSet<>();
        allIndexes.addAll(succeededPerIndex.keySet());
        allIndexes.addAll(failedPerIndex.keySet());
        allIndexes.addAll(skippedPerIndex.keySet());
        if (allIndexes.isEmpty()) {
            log.info("[cycle-summary] Aucun fichier traité ce cycle (durée: {})", formatDuration(lastCycleTotalMs));
            return;
        }

        // Group per-cycle file lists by index
        Map<String, List<IndexingError>> failuresByIndex = new LinkedHashMap<>();
        for (IndexingError e : currentCycleFailures) {
            failuresByIndex.computeIfAbsent(e.coreName(), k -> new ArrayList<>()).add(e);
        }
        Map<String, List<String>> skippedByIndex = new LinkedHashMap<>();
        for (SkippedFile s : currentCycleSkipped) {
            skippedByIndex.computeIfAbsent(s.coreName(), k -> new ArrayList<>()).add(s.path());
        }

        log.info("[cycle-summary] {}", SEP);
        for (String index : allIndexes.stream().sorted().toList()) {
            int ok   = getCount(succeededPerIndex, index);
            int fail = getCount(failedPerIndex,    index);
            int skip = getCount(skippedPerIndex,   index);
            long txtPg = getPageCount(textPagesPerIndex, index);
            long ocrPg = getPageCount(ocrPagesPerIndex,  index);
            boolean hasProblems = fail > 0 || skip > 0;

            String pagesSummary = buildPagesSummary(txtPg, ocrPg);

            if (hasProblems) {
                log.warn("[cycle-summary] Index '{}' : {} indexé(s)  |  {} erreur(s)  |  {} ignoré(s){}",
                        index, ok, fail, skip, pagesSummary);
            } else {
                log.info("[cycle-summary] Index '{}' : {} indexé(s){}",
                        index, ok, pagesSummary);
            }

            List<IndexingError> failures = failuresByIndex.getOrDefault(index, List.of());
            if (!failures.isEmpty()) {
                log.warn("[cycle-summary]   Erreurs ({}) :", failures.size());
                for (IndexingError e : failures) {
                    log.warn("[cycle-summary]     {}  —  {}", e.path(), e.message());
                }
                if (getCount(failedPerIndex, index) > failures.size()) {
                    log.warn("[cycle-summary]     (liste tronquée — cap mémoire de {} atteint)", MAX_CYCLE_FILES);
                }
            }

            List<String> skipped = skippedByIndex.getOrDefault(index, List.of());
            if (!skipped.isEmpty()) {
                log.warn("[cycle-summary]   Ignorés — aucun contenu extractible ({}) :", skipped.size());
                for (String path : skipped) {
                    log.warn("[cycle-summary]     {}", path);
                }
                if (getCount(skippedPerIndex, index) > skipped.size()) {
                    log.warn("[cycle-summary]     (liste tronquée — cap mémoire de {} atteint)", MAX_CYCLE_FILES);
                }
            }
        }

        // Global speed summary
        log.info("[cycle-summary] {}", SEP);
        long totalPages = lastCycleTextPages + lastCycleOcrPages;
        double durationMin = lastCycleTotalMs / 60_000.0;
        if (durationMin > 0 && totalPages > 0) {
            log.info("[cycle-summary] Durée : {}  |  Fichiers : {}  |  Pages indexées : {}  (texte: {}  OCR: {})  |  Vitesse : {} p/min",
                    formatDuration(lastCycleTotalMs),
                    lastCycleFilesProcessed,
                    totalPages,
                    lastCycleTextPages,
                    lastCycleOcrPages,
                    String.format("%.1f", totalPages / durationMin));
        } else {
            log.info("[cycle-summary] Durée : {}  |  Fichiers : {}  |  Aucune page indexée",
                    formatDuration(lastCycleTotalMs), lastCycleFilesProcessed);
        }
        if (lastCycleOcrPages > 0 && lastCycleOcrActiveMs > 0) {
            double ocrActiveMin = lastCycleOcrActiveMs / 60_000.0;
            log.info("[cycle-summary] OCR : {} p. traitées en {} de temps actif  =  {} p/min",
                    lastCycleOcrPages,
                    formatDuration(lastCycleOcrActiveMs),
                    String.format("%.1f", lastCycleOcrPages / ocrActiveMin));
        }
        log.info("[cycle-summary] {}", SEP);
    }

    private static String formatDuration(long ms) {
        if (ms <= 0) return "0s";
        long totalSec = ms / 1000;
        long min = totalSec / 60;
        long sec = totalSec % 60;
        return min > 0 ? min + "m " + sec + "s" : sec + "s";
    }

    private static String buildPagesSummary(long textPages, long ocrPages) {
        long total = textPages + ocrPages;
        if (total == 0) return "";
        if (ocrPages == 0) return "  |  " + textPages + " p. texte";
        if (textPages == 0) return "  |  " + ocrPages + " p. OCR";
        return "  |  " + total + " p. (" + textPages + " texte + " + ocrPages + " OCR)";
    }

    private long getPageCount(ConcurrentHashMap<String, AtomicLong> map, String key) {
        AtomicLong v = map.get(key);
        return v != null ? v.get() : 0;
    }

    private int getCount(ConcurrentHashMap<String, AtomicInteger> map, String key) {
        AtomicInteger v = map.get(key);
        return v != null ? v.get() : 0;
    }

    /** Called when a file is successfully enqueued and marked indexed in the registry. */
    public void recordSuccess(String coreName, String path) {
        succeededPerIndex.computeIfAbsent(coreName, k -> new AtomicInteger()).incrementAndGet();
        errorDao.deleteByPath(path);
    }

    /** Called when a file could not be indexed; persists to DB and to the current-cycle list. */
    public void recordFailure(String coreName, String path, String message) {
        failedPerIndex.computeIfAbsent(coreName, k -> new AtomicInteger()).incrementAndGet();
        Instant now = Instant.now(clock);
        errorDao.insert("error", now, path, message, coreName);
        if (currentCycleFailures.size() < MAX_CYCLE_FILES) {
            currentCycleFailures.add(new IndexingError(now, path, message, coreName));
        }
    }

    /** Called when a file produced no content (empty document, no extractable text); persists to DB. */
    public void recordSkipped(String coreName, String path) {
        skippedPerIndex.computeIfAbsent(coreName, k -> new AtomicInteger()).incrementAndGet();
        Instant now = Instant.now(clock);
        errorDao.insert("skipped", now, path, "Aucun contenu extractible", coreName);
        if (currentCycleSkipped.size() < MAX_CYCLE_FILES) {
            currentCycleSkipped.add(new SkippedFile(path, coreName, now));
        }
    }

    /**
     * Called when a file was indexed, but only in part: too large to read (indexed by its name),
     * or its text cut at the limit. Listed in the admin panel; counts as indexed.
     */
    public void recordLimited(String coreName, String path, String message) {
        errorDao.insert("limited", Instant.now(clock), path, message, coreName);
    }

    /**
     * Called when a whole source could not be read — an SMB share that refuses the
     * connection, a local directory that has gone missing. No file of that source is
     * ever attempted, so it produces no per-file failure and the panel would otherwise
     * report a clean registry while nothing at all had been read.
     *
     * One row per source, replaced at each attempt: a share that stays down for days
     * must not push the files' own errors out of the table (IndexingErrorDao.MAX_ROWS).
     * The row carries no file count — it belongs to no cycle summary, only to the
     * problem list.
     */
    public void recordSourceFailure(String coreName, String source, String message) {
        errorDao.deleteSourceRow(source, coreName);
        errorDao.insert("source", Instant.now(clock), source, message, coreName);
    }

    /**
     * Called when a source was read but its files could not be recorded in the registry.
     * Listed with the file errors, as one row against the source; it counts as no failed
     * file, since no file was tried.
     */
    public void recordFeedingFailure(String coreName, String source, String message) {
        errorDao.insert("error", Instant.now(clock), source, message, coreName);
    }

    /** Called when a source is read again; clears the row {@link #recordSourceFailure} left. */
    public void clearSourceFailure(String coreName, String source) {
        errorDao.deleteSourceRow(source, coreName);
    }

    /**
     * Keeps only the source problems of sources the configuration still names. Called at
     * the start of a cycle: an admin who corrects a share's address, or drops an index,
     * must not be left looking at a problem against something nothing reads any more.
     */
    public void pruneSourceFailures(Collection<IndexingErrorDao.Source> configured) {
        errorDao.retainSourceRows(configured);
    }

    /** Returns all error/skipped rows with a single DB read. */
    public List<IndexingErrorDao.Row> getErrorRows() {
        return errorDao.findAll();
    }

    public List<IndexingError> getRecentErrors() {
        return errorDao.findAll().stream()
                .filter(r -> "error".equals(r.kind()))
                .map(r -> new IndexingError(r.timestamp(), r.path(), r.message(), r.coreName()))
                .toList();
    }

    public List<SkippedFile> getRecentSkipped() {
        return errorDao.findAll().stream()
                .filter(r -> "skipped".equals(r.kind()))
                .map(r -> new SkippedFile(r.path(), r.coreName(), r.timestamp()))
                .toList();
    }

    public Map<String, IndexCycleSummary> getIndexSummaries() {
        Set<String> allIndexes = new HashSet<>();
        allIndexes.addAll(succeededPerIndex.keySet());
        allIndexes.addAll(failedPerIndex.keySet());
        allIndexes.addAll(skippedPerIndex.keySet());
        Map<String, IndexCycleSummary> result = new LinkedHashMap<>();
        for (String index : allIndexes.stream().sorted().toList()) {
            result.put(index, new IndexCycleSummary(
                getCount(succeededPerIndex,    index),
                getCount(failedPerIndex,       index),
                getCount(skippedPerIndex,      index),
                getPageCount(textPagesPerIndex, index),
                getPageCount(ocrPagesPerIndex,  index),
                spanMs(index),
                ocrActiveMs(index)
            ));
        }
        return result;
    }

    private long spanMs(String index) {
        long[] span = spanPerIndex.get(index);
        return span == null || span[1] < span[0] ? 0 : span[1] - span[0];
    }

    private long ocrActiveMs(String index) {
        BusyClock busy = ocrBusyPerIndex.get(index);
        return busy == null ? 0 : busy.busyMs();
    }

    public ProgressSnapshot getSnapshot() {
        return new ProgressSnapshot(
                running.get(),
                cycleTotal.get(),
                cycleProcessed.get(),
                textPagesTotal.get(),
                ocrPagesTotal.get(),
                cycleTextPages.get(),
                cycleOcrPages.get(),
                ocrBusy.busyMs(),
                appStartedAt,
                cycleStartedAt,
                lastCycleCompletedAt,
                Map.copyOf(activeFiles),
                lastFileCompletedAt,
                lastRound
        );
    }

    public record ProgressSnapshot(
            boolean running,
            long cycleTotal,
            long cycleProcessed,
            long textPagesTotal,
            long ocrPagesTotal,
            long cycleTextPages,
            long cycleOcrPages,
            long cycleOcrActiveMs,
            Instant appStartedAt,
            Instant cycleStartedAt,
            Instant lastCycleCompletedAt,
            Map<String, Instant> activeFiles,
            Instant lastFileCompletedAt,
            LastRound lastRound
    ) {}

    /**
     * durationMs: from the index's first file started to its last file completed, in the
     * cycles where it had files (text pages/min). ocrActiveMs: time during which a page of
     * the index was being OCRed (scanned pages/min).
     */
    public record IndexCycleSummary(int succeeded, int failed, int skipped, long textPages, long ocrPages,
                                    long durationMs, long ocrActiveMs) {}

    /**
     * The last round that processed files. durationMs sums the cycles that did work (the
     * text pages/min denominator); ocrActiveMs is the time during which at least one page
     * was being OCRed (the scanned pages/min denominator, not diluted by text files).
     */
    public record LastRound(
            Instant completedAt,
            long durationMs,
            long files,
            long textPages,
            long ocrPages,
            long ocrActiveMs,
            Map<String, IndexCycleSummary> indexes
    ) {}

    public record IndexingError(
            Instant timestamp,
            String path,
            String message,
            String coreName
    ) {}

    public record SkippedFile(String path, String coreName, Instant timestamp) {}
}
