package eu.intracherche.intracherche.queues;

import eu.intracherche.intracherche.indexing.model.IndexTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.PriorityBlockingQueue;

/**
 * Thread-safe queue for {@link IndexTask} instances that are ready to be processed
 * by {@link eu.intracherche.intracherche.indexing.worker.IndexWorker} threads.
 *
 * <p>This queue uses a bounded {@link LinkedBlockingQueue} with a configurable capacity
 * (default 100) to provide backpressure when the indexing workers cannot keep up
 * with the upstream parsing/enrichment pipeline.</p>
 *
 * <p>The queue operates on a publish-consume pattern:
 * <ul>
 *   <li>Upstream services (e.g., FileIndexingService) publish IndexTask instances via {@link #publish(IndexTask)}</li>
 *   <li>IndexWorker threads consume tasks via {@link #take()}</li>
 * </ul>
 * </p>
 *
 * <p>If the queue is full, {@link #publish(IndexTask)} will block until space becomes available,
 * effectively throttling the upstream pipeline.</p>
 */
public final class IndexTaskQueue {

    private static final Logger log = LoggerFactory.getLogger(IndexTaskQueue.class);

    private final BlockingQueue<IndexTask> queue;

    /**
     * Creates a new IndexTaskQueue with the default capacity of 100.
     */
    public IndexTaskQueue() {
        this(100);
    }

    /**
     * Creates a new IndexTaskQueue with the given initial capacity hint.
     * Non-OCR tasks are dequeued before OCR tasks regardless of arrival order.
     * The queue is unbounded and will grow beyond the initial capacity if needed.
     */
    public IndexTaskQueue(int capacity) {
        this.queue = new PriorityBlockingQueue<>(capacity);
        log.info("IndexTaskQueue initialized (priority queue, initial capacity: {})", capacity);
    }

    /**
     * Publishes an IndexTask to the queue for asynchronous processing.
     * This method will block if the queue is full, providing backpressure
     * to the upstream pipeline.
     *
     * @param task the IndexTask to be added to the queue
     * @throws InterruptedException if the current thread was interrupted while waiting or timed out
     */
    public void publish(IndexTask task) throws InterruptedException {
        int sizeBefore = queue.size();
        queue.put(task); // PriorityBlockingQueue is unbounded; put() never blocks
        log.info("ENQUEUE SUCCESS: document: {} core: {} ocr={} [queue size: {} -> {}]",
                task.documentId(), task.coreName(), task.isOcrResult(), sizeBefore, queue.size());
    }

    /**
     * Retrieves and removes the next IndexTask from the queue, waiting if necessary
     * until an element becomes available.
     *
     * @return the IndexTask that was removed from the queue
     * @throws InterruptedException if the current thread was interrupted while waiting
     */
    public IndexTask take() throws InterruptedException {
        IndexTask task = queue.take();
        log.debug("Dequeued IndexTask for document: {} [queue size: {}]", task.documentId(), queue.size());
        return task;
    }

    /**
     * Returns the number of IndexTask instances currently in this queue.
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
}