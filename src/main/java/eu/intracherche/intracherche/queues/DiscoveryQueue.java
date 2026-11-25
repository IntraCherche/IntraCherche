package eu.intracherche.intracherche.queues;

import eu.intracherche.intracherche.common.events.FileDiscoveredEvent;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

public final class DiscoveryQueue {

    private final BlockingQueue<FileDiscoveredEvent> queue;

    public DiscoveryQueue(int capacity) {
        this.queue = new ArrayBlockingQueue<>(capacity);
    }

    /**
     * Puts a discovered file event into the queue for further processing.
     * This method will block if the queue is full, providing backpressure.
     * @param event
     * @throws InterruptedException
     */
    public void publish(FileDiscoveredEvent event) throws InterruptedException {
        queue.put(event);
    }

    /**
     * Retrieves and removes the FileDiscoveredEvent from the queue, waiting if necessary until an element becomes available.
     * @return the FileDiscoveredEvent that was removed from the queue
     * @throws InterruptedException if the current thread was interrupted while waiting
     */
    public FileDiscoveredEvent take() throws InterruptedException {
        return queue.take();
    }

    /** Drops every event still waiting, when the worker that would have read them is gone. */
    public void clear() {
        queue.clear();
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

