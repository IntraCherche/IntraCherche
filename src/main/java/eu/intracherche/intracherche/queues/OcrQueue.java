package eu.intracherche.intracherche.queues;

import eu.intracherche.intracherche.indexing.model.OCRTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Thread-safe queue for {@link OCRTask} instances that are consumed by
 * asynchronous OCR workers.
 *
 * <p>This queue uses a bounded {@link LinkedBlockingQueue} with a capacity of 100
 * to provide backpressure when OCR workers cannot keep up with the upstream
 * parsing pipeline.</p>
 *
 * <p>When a scanned PDF page is detected during parsing, an OCRTask is enqueued
 * here instead of blocking the parsing thread on synchronous OCR. Dedicated OCR
 * workers consume tasks from this queue, perform OCR via the RapidOCR integration,
 * and enqueue the resulting IndexTask into the indexing queue.</p>
 */
public final class OcrQueue {

    private static final Logger log = LoggerFactory.getLogger(OcrQueue.class);

    /** Default capacity for the OCR queue. */
    private static final int DEFAULT_CAPACITY = 100;

    private final BlockingQueue<OCRTask> queue;

    /**
     * Creates a new OcrQueue with the default capacity of 100.
     */
    public OcrQueue() {
        this(DEFAULT_CAPACITY);
    }

    /**
     * Creates a new OcrQueue with the specified capacity.
     *
     * @param capacity the maximum number of OCRTask instances that this queue can hold
     */
    public OcrQueue(int capacity) {
        this.queue = new LinkedBlockingQueue<>(capacity);
        log.info("OcrQueue initialized with capacity: {}", capacity);
    }

    /**
     * Publishes an OCRTask to the queue for asynchronous OCR processing.
     * Blocks if the queue is full, providing true backpressure to the upstream
     * parsing pipeline. Only throws InterruptedException on a real thread interrupt
     * (e.g. shutdown), never on a mere capacity condition.
     *
     * @param task the OCRTask to be added to the queue
     * @throws InterruptedException if the current thread was interrupted while waiting
     */
    public void publish(OCRTask task) throws InterruptedException {
        int remaining = queue.remainingCapacity();
        if (remaining <= 10) {
            log.warn("OcrQueue is nearly full (remaining capacity: {}), producer will block until a worker frees space", remaining);
        }
        queue.put(task);
        log.debug("Enqueued OCRTask file: {} page: {} [queue size: {}]",
                task.filePath(), task.pageNumber(), queue.size());
    }

    /**
     * Retrieves and removes the next OCRTask from the queue, waiting if necessary
     * until an element becomes available.
     *
     * @return the OCRTask that was removed from the queue
     * @throws InterruptedException if the current thread was interrupted while waiting
     */
    public OCRTask take() throws InterruptedException {
        OCRTask task = queue.take();
        log.debug("Dequeued OCRTask for file: {} page: {} [queue size: {}]",
                task.filePath(), task.pageNumber(), queue.size());
        return task;
    }

    /**
     * Returns the number of OCRTask instances currently in this queue.
     *
     * @return the number of elements in this queue
     */
    public int size() {
        return queue.size();
    }

    /**
     * Returns the remaining capacity in this queue.
     *
     * @return the number of additional elements that this queue can hold
     */
    public int remainingCapacity() {
        return queue.remainingCapacity();
    }

    /**
     * Returns whether this queue is empty.
     *
     * @return true if this queue contains no elements
     */
    public boolean isEmpty() {
        return queue.isEmpty();
    }

    /**
     * Removes and returns all tasks currently in the queue without waiting.
     * Used on pause to discard pending work so in-flight OCR threads finish
     * and the cycle unblocks immediately. Drained tasks are retried next run.
     */
    public List<OCRTask> drain() {
        List<OCRTask> drained = new ArrayList<>();
        queue.drainTo(drained);
        if (!drained.isEmpty()) {
            log.info("OcrQueue drained: {} task(s) removed (will be retried next run)", drained.size());
        }
        return drained;
    }
}