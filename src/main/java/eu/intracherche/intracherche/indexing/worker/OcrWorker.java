package eu.intracherche.intracherche.indexing.worker;

import eu.intracherche.intracherche.indexing.model.OCRTask;
import eu.intracherche.intracherche.indexing.model.IndexTask;
import eu.intracherche.intracherche.indexing.outage.ServiceOutageMonitor;
import eu.intracherche.intracherche.indexing.outage.ServiceOutageMonitor.Dependency;
import eu.intracherche.intracherche.indexing.outage.ServiceUnavailableException;
import eu.intracherche.intracherche.queues.IndexTaskQueue;
import eu.intracherche.intracherche.queues.OcrQueue;
import eu.intracherche.intracherche.services.EmbeddingService;
import eu.intracherche.intracherche.services.OcrService;
import org.apache.tika.metadata.Metadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;

/**
 * Dedicated worker that consumes {@link OCRTask} instances from an {@link OcrQueue},
 * performs OCR using the existing RapidOCR integration, and enqueues the resulting
 * {@link IndexTask} into the {@link IndexTaskQueue} for indexing.
 *
 * <p>Each OcrWorker runs continuously in its own thread, pulling OCRTask instances
 * from the shared queue, rendering the PDF page to an image, calling the OCR service,
 * embedding the extracted text, and then creating an IndexTask with the OCR-extracted text
 * and its vector.</p>
 *
 * <p>The embedding is computed here because scanned pages bypass the enrichment pipeline
 * ({@code VectorEnricher}) that gives native pages their vector, and {@link IndexWorker} only
 * writes the vector it is handed. Without it, OCR pages were invisible to the kNN leg of hybrid
 * search and sank in the RRF fusion even when they were the top BM25 hit.</p>
 *
 * <p>When the OCR or the embedding service is down, the worker holds its page, waits for the
 * service to come back and carries on (see {@link ServiceOutageMonitor}): the page is neither
 * lost nor OCRed again. As every worker waits, the queue fills and the indexing threads that
 * feed it wait too.</p>
 *
 * <p>This class does NOT access Solr directly. Solr access remains centralized
 * inside {@link IndexWorker} only.</p>
 */
public class OcrWorker implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(OcrWorker.class);

    private final String workerName;
    private final OcrService ocrService;
    private final EmbeddingService embeddingService;
    private final OcrQueue ocrQueue;
    private final IndexTaskQueue indexTaskQueue;
    private final DocumentCompletionTracker completionTracker;
    /** Null: no waiting, a failed service call is handled at once (tests). */
    private final ServiceOutageMonitor outageMonitor;
    private volatile boolean running = true;

    public OcrWorker(String workerName,
                     OcrService ocrService,
                     EmbeddingService embeddingService,
                     OcrQueue ocrQueue,
                     IndexTaskQueue indexTaskQueue,
                     DocumentCompletionTracker completionTracker) {
        this(workerName, ocrService, embeddingService, ocrQueue, indexTaskQueue, completionTracker, null);
    }

    public OcrWorker(String workerName,
                     OcrService ocrService,
                     EmbeddingService embeddingService,
                     OcrQueue ocrQueue,
                     IndexTaskQueue indexTaskQueue,
                     DocumentCompletionTracker completionTracker,
                     ServiceOutageMonitor outageMonitor) {
        this.outageMonitor = outageMonitor;
        this.workerName = workerName;
        this.ocrService = ocrService;
        this.embeddingService = embeddingService;
        this.ocrQueue = ocrQueue;
        this.indexTaskQueue = indexTaskQueue;
        this.completionTracker = completionTracker;
    }

    /**
     * Signals this worker to stop processing after the current task completes.
     */
    public void stop() {
        this.running = false;
    }

    /**
     * Returns whether this worker is still running.
     */
    public boolean isRunning() {
        return running;
    }

    /**
     * Returns the name of this worker.
     */
    public String getWorkerName() {
        return workerName;
    }

    @Override
    public void run() {
        log.info("[{}] OcrWorker started", workerName);
        while (running) {
            try {
                OCRTask task = ocrQueue.take();
                if (task != null) {
                    processOcrTask(task);
                }
            } catch (InterruptedException e) {
                log.info("[{}] OcrWorker interrupted, shutting down gracefully", workerName);
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("[{}] Unexpected error in OcrWorker loop: {}", workerName, e.getMessage(), e);
            }
        }
        log.info("[{}] OcrWorker stopped", workerName);
    }

    /**
     * Processes a single OCRTask by sending the pre-rendered PNG to the OCR service
     * and enqueuing an IndexTask with the extracted text.
     */
    void processOcrTask(OCRTask task) {
        String docId = task.documentId();
        int pageNum = task.pageNumber();
        Metadata metadata = task.metadata();

        log.info("[{}] Processing OCRTask: file={}, page={}, pngSize={} bytes",
                workerName, task.filePath(), pageNum, task.pngBytes() != null ? task.pngBytes().length : 0);

        boolean pageSettled = false;

        try {
            PageText page = readPage(task);
            String ocrText = page.text();
            long duration = page.durationMs();

            if (ocrText == null || ocrText.isEmpty()) {
                log.warn("[{}] OCR returned empty result for document={} page={} in {} ms",
                        workerName, docId, pageNum, duration);
                return;
            }

            log.info("[{}] OCR extracted {} characters from file={} page={} in {} ms",
                    workerName, ocrText.length(), task.filePath(), pageNum, duration);

            IndexTask.SectionContent section = new IndexTask.SectionContent(
                    "Page " + pageNum, ocrText, true, pageNum
            );

            // Null when the embedding service is disabled or failed. A page indexed without its
            // vector would stay out of the kNN leg for good, since the document would be marked
            // indexed: when embedding is enabled, the worker waits out an outage and embeds the
            // page then; if the service answers but still fails, the page is not sent and the
            // document stays pending for the next cycle — the same rule as a native page.
            float[] vector;
            while ((vector = embeddingService.embed(ocrText, false)) == null && embeddingService.isEnabled()) {
                if (outageMonitor == null || !outageMonitor.confirmOutage(Dependency.EMBEDDING)) {
                    log.warn("[{}] No embedding for document={} page={}: document left pending for the next cycle",
                            workerName, docId, pageNum);
                    completionTracker.ocrPageMissing(docId);
                    pageSettled = true;
                    return;
                }
                outageMonitor.awaitRecovery(Dependency.EMBEDDING, () -> !running);
            }

            IndexTask indexTask = new IndexTask(
                    docId,
                    task.coreName(),
                    task.filePath(),
                    List.of(section),
                    metadata,
                    task.accessTokens(),
                    task.denyTokens(),
                    task.mtime(),
                    task.smbServer(),
                    task.smbShare(),
                    Collections.singletonList(vector),
                    true
            );

            indexTaskQueue.publish(indexTask);
            pageSettled = true;
            log.info("[{}] Enqueued IndexTask for OCR result: document={} page={}",
                    workerName, docId, pageNum);

        } catch (InterruptedException e) {
            log.warn("[{}] Interrupted while enqueuing IndexTask for document={} page={}",
                    workerName, docId, pageNum);
            Thread.currentThread().interrupt();
        } catch (ServiceUnavailableException e) {
            // Stopped while waiting for the service: the page was not indexed, and the
            // document must not be marked indexed without it.
            log.info("[{}] Stopped waiting for the {} service: document={} page={} left pending for the next cycle",
                    workerName, e.dependency().id, docId, pageNum);
            completionTracker.ocrPageMissing(docId);
            pageSettled = true;
        } catch (Exception e) {
            log.error("[{}] Failed to process OCRTask for document={} page={}: {}",
                    workerName, docId, pageNum, e.getMessage(), e);
        } finally {
            // Only decrement here if the page was not settled above: an enqueued IndexTask is
            // settled by IndexWorker's solrPageDone() after the Solr write, so markIndexed fires
            // only once every page is actually written to the index.
            if (!pageSettled) {
                completionTracker.ocrPageFailed(docId);
            }
        }
    }

    private record PageText(String text, long durationMs) {}

    /**
     * OCRs the page, waiting out an outage of the OCR service and trying again. A failure while
     * the service answers is thrown: that page is one the service cannot read. The duration is
     * the successful attempt's only, so an outage does not skew the OCR throughput.
     */
    private PageText readPage(OCRTask task) throws ServiceUnavailableException {
        while (true) {
            long start = System.currentTimeMillis();
            // Only the request is timed: waiting for the service to come back is not OCR time
            completionTracker.ocrPageStarted(task.documentId());
            try {
                String text = ocrService.extractTextFromImage(task.pngBytes());
                completionTracker.ocrPageFinished(task.documentId());
                return new PageText(text, System.currentTimeMillis() - start);
            } catch (RuntimeException e) {
                completionTracker.ocrPageFinished(task.documentId());
                if (outageMonitor == null || !outageMonitor.confirmOutage(Dependency.OCR)) throw e;
                outageMonitor.awaitRecovery(Dependency.OCR, () -> !running);
            }
        }
    }
}
