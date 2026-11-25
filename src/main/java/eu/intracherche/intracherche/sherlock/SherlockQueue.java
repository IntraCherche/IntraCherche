package eu.intracherche.intracherche.sherlock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public class SherlockQueue {

    private static final Logger log = LoggerFactory.getLogger(SherlockQueue.class);

    private final LinkedBlockingQueue<SherlockQuestion> queue;

    public SherlockQueue(int capacity) {
        this.queue = new LinkedBlockingQueue<>(capacity);
    }

    public boolean publish(SherlockQuestion question) throws InterruptedException {
        boolean accepted = queue.offer(question, 5, TimeUnit.SECONDS);
        if (accepted) {
            log.info("Sherlock question queued: {} ({})", question.id(), question.username());
        } else {
            log.warn("Sherlock queue full — question {} rejected after 5s wait", question.id());
        }
        return accepted;
    }

    public SherlockQuestion take() throws InterruptedException {
        SherlockQuestion q = queue.take();
        log.debug("Sherlock question dequeued: {}", q.id());
        return q;
    }

    public int size() { return queue.size(); }
    public boolean isEmpty() { return queue.isEmpty(); }
}
