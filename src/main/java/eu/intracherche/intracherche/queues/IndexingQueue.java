package eu.intracherche.intracherche.queues;

import eu.intracherche.intracherche.registry.FileRecord;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

public final class IndexingQueue {

    private final BlockingQueue<FileRecord> queue;

    /**
     * Creates a new indexing queue with the specified capacity.
     *
     * @param capacity the maximum number of elements that this queue can hold
     */
    public IndexingQueue(int capacity) {
        this.queue = new ArrayBlockingQueue<>(capacity);
    }

    /**
     * Adds a file record to the indexing queue for processing.
     * This method will block if the queue is full, providing backpressure.
     *
     * @param file the file record to be added to the queue
     * @throws InterruptedException if the current thread was interrupted while waiting
     */
    public void publish(FileRecord file) throws InterruptedException {
        queue.put(file);
    }

    /**
     * Retrieves and removes the FileRecord from the queue, waiting if necessary until an element becomes available.
     *
     * @return the FileRecord that was removed from the queue
     * @throws InterruptedException if the current thread was interrupted while waiting
     */
    public FileRecord take() throws InterruptedException {
        return queue.take();
    }

    /**
     * Returns the number of elements in this queue.
     *
     * @return the number of elements in this queue
     */
    public int size() {
        return queue.size();
    }
}

