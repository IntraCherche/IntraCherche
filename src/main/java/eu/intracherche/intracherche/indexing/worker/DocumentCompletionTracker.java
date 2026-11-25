package eu.intracherche.intracherche.indexing.worker;

import eu.intracherche.intracherche.registry.FileRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tracks per-document OCR page completion and marks the document as indexed
 * in the registry only once every enqueued page has been attempted.
 *
 * <p>Usage:
 * <ol>
 *   <li>Call {@link #register(String, int)} before enqueuing OCR tasks,
 *       passing the total number of pages that will be processed.</li>
 *   <li>Each {@link OcrWorker} calls {@link #ocrPageFailed(String)} when a page
 *       produced no IndexTask (OCR failed or returned empty).</li>
 *   <li>Each {@link IndexWorker} calls {@link #solrPageDone(String, boolean, boolean)} after
 *       attempting the Solr write, passing whether the write succeeded.</li>
 *   <li>A document with extracted text is registered with {@link #expectWrites(String, int)}
 *       for its Solr writes, so it too is marked indexed only once they are done.</li>
 *   <li>When the last page completes the pending counter reaches zero.
 *       {@link FileRegistry#markIndexed(String)} is called only if at least one
 *       page was successfully written to Solr and Solr refused none; otherwise the document is left
 *       unindexed so it is retried on the next run.</li>
 * </ol>
 * </p>
 */
@Component
public class DocumentCompletionTracker {

    private static final Logger log = LoggerFactory.getLogger(DocumentCompletionTracker.class);

    private final ConcurrentHashMap<String, AtomicInteger> pending = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> successCount = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String>        coreNames = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String>        ocrPaths = new ConcurrentHashMap<>();
    // Documents a page of which is missing from the index: a Solr write failed, or a
    // scanned page could not be read or embedded.
    private final Set<String>                             incomplete = ConcurrentHashMap.newKeySet();
    private final FileRegistry fileRegistry;
    private final IndexingProgressTracker progressTracker;
    private final Object completionLock = new Object();

    public DocumentCompletionTracker(FileRegistry fileRegistry, IndexingProgressTracker progressTracker) {
        this.fileRegistry = fileRegistry;
        this.progressTracker = progressTracker;
    }

    public void register(String documentId, int totalPages, String coreName, String path) {
        pending.put(documentId, new AtomicInteger(totalPages));
        successCount.put(documentId, new AtomicInteger(0));
        if (coreName != null) coreNames.put(documentId, coreName);
        if (path != null) ocrPaths.put(documentId, path);
        log.debug("Registered {} OCR pages pending for document {} (core: {})", totalPages, documentId, coreName);
    }

    /**
     * Called by OcrWorker when a page produced no IndexTask: OCR found no text, or failed on
     * that page while the service answered — retrying would only fail again. The slot is
     * counted as done immediately since no Solr write will follow.
     */
    public void ocrPageFailed(String documentId) {
        decrementAndCheck(documentId, "OCR-failed page");
    }

    /**
     * Called by OcrWorker when a scanned page with text could not be indexed: its embedding
     * failed, or the worker stopped waiting for a service that was down. Unlike
     * {@link #ocrPageFailed}, the document stays pending: marked indexed, it would never be
     * sent again and the page would stay missing for good.
     */
    public void ocrPageMissing(String documentId) {
        if (successCount.containsKey(documentId)) incomplete.add(documentId);
        decrementAndCheck(documentId, "OCR page missing");
    }

    /** Called by OcrWorker around each request to the OCR service, to time OCR per index. */
    public void ocrPageStarted(String documentId) {
        progressTracker.ocrPageStarted(coreNames.getOrDefault(documentId, "?"));
    }

    public void ocrPageFinished(String documentId) {
        progressTracker.ocrPageFinished(coreNames.getOrDefault(documentId, "?"));
    }

    /**
     * Registers the Solr writes FileIndexingService is about to enqueue for a document with
     * extracted text, one per core, before the first of them is published. The document is
     * then marked indexed once IndexWorker has written them — not at enqueue time, when a
     * failed write or a shutdown with tasks still queued left it marked indexed yet absent
     * from Solr, and never retried.
     * <p>
     * Adds to an existing registration: a PDF mixing text and scanned pages was registered
     * for its OCR pages during parsing and must wait for both.
     */
    public void expectWrites(String documentId, int writes) {
        pending.compute(documentId, (k, v) -> {
            if (v == null) {
                v = new AtomicInteger();
                successCount.put(documentId, new AtomicInteger());
            }
            v.addAndGet(writes);
            return v;
        });
    }

    /**
     * Called by IndexWorker after attempting a Solr write, for every task it processes.
     * {@code succeeded} must be true only when Solr acknowledged the write without error;
     * {@code ocrPage} is {@link eu.intracherche.intracherche.indexing.model.IndexTask#isOcrResult()}.
     */
    public void solrPageDone(String documentId, boolean succeeded, boolean ocrPage) {
        AtomicInteger sc = successCount.get(documentId);
        if (sc != null) {
            if (succeeded) sc.incrementAndGet();
            else incomplete.add(documentId);
        }
        // Only an OCR page is a scanned page: counting text writes too made every text file
        // look like one and inflated the admin panel's scan throughput.
        if (succeeded && ocrPage) {
            progressTracker.recordOcrPage(coreNames.getOrDefault(documentId, "?"));
        }
        decrementAndCheck(documentId, succeeded ? "Solr-written page (ok)" : "Solr-written page (failed)");
    }

    private void decrementAndCheck(String documentId, String context) {
        // Decrement and removal happen atomically so a concurrent expectWrites() cannot add
        // to a counter that is about to be discarded.
        int[] left = {-1};
        AtomicInteger[] sc = {null};
        boolean[] anyMissing = {false};
        pending.computeIfPresent(documentId, (k, v) -> {
            left[0] = v.decrementAndGet();
            if (left[0] > 0) return v;
            sc[0] = successCount.remove(documentId);
            anyMissing[0] = incomplete.remove(documentId);
            return null;
        });
        if (left[0] < 0) {
            return;
        }
        if (left[0] > 0) {
            log.debug("{} page(s) still pending for document {} ({})", left[0], documentId, context);
            return;
        }
        coreNames.remove(documentId);
        String path = ocrPaths.remove(documentId);
        int succeeded = sc[0] != null ? sc[0].get() : 0;

        if (succeeded == 0) {
            log.warn("All OCR/Solr attempts failed for document {} — leaving unindexed for retry on next run",
                    documentId);
        } else if (anyMissing[0]) {
            // A page or core Solr refused, or a scanned page that could not be indexed, is
            // missing from the index: marking the document indexed would never send it again.
            log.warn("Part of document {} is missing from the index ({} write(s) succeeded) — leaving unindexed for retry on next run",
                    documentId, succeeded);
        } else {
            try {
                fileRegistry.markIndexed(documentId);
                log.info("All pages done for document {} ({} succeeded); marked as indexed", documentId, succeeded);
            } catch (Exception e) {
                log.warn("Failed to mark document {} as indexed", documentId, e);
            }
        }

        if (path != null) {
            progressTracker.fileCompleted(path);
        }

        synchronized (completionLock) {
            if (pending.isEmpty()) {
                completionLock.notifyAll();
            }
        }
    }

    /**
     * Cancels all pending document registrations and wakes any thread blocked in
     * {@link #waitForCompletion}. Called on indexer pause so the current cycle
     * unblocks immediately. Affected documents are left unindexed and retried on
     * the next run, identical to the existing timeout behaviour.
     */
    public void cancelAllPending() {
        int count = pending.size();
        List<String> remainingPaths = new ArrayList<>(ocrPaths.values());
        pending.clear();
        successCount.clear();
        coreNames.clear();
        ocrPaths.clear();
        incomplete.clear();
        for (String path : remainingPaths) {
            progressTracker.fileCompleted(path);
        }
        synchronized (completionLock) {
            completionLock.notifyAll();
        }
        if (count > 0) {
            log.info("Cancelled {} pending OCR document(s) due to pause; will retry next run", count);
        }
    }

    /**
     * Blocks until all registered OCR documents have been fully processed, or until
     * the timeout elapses. Returns immediately if no documents are pending.
     */
    public void waitForCompletion(long timeoutSeconds) throws InterruptedException {
        long deadlineMs = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(timeoutSeconds);
        synchronized (completionLock) {
            while (!pending.isEmpty()) {
                long remainingMs = deadlineMs - System.currentTimeMillis();
                if (remainingMs <= 0) {
                    log.warn("Timed out waiting for OCR completion; {} document(s) still pending: {}",
                            pending.size(), pending.keySet());
                    return;
                }
                completionLock.wait(remainingMs);
            }
        }
        log.info("All pending OCR documents completed");
    }
}
