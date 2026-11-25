package eu.intracherche.intracherche.services;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Bounds the calls in flight to the embedding service, serving user-facing callers first.
 *
 * <p>A plain semaphore queues callers in arrival order, so a search arriving during a large
 * indexing run waited behind every batch already queued — minutes, on a CPU-only server.
 * Here a priority caller (a search, a Sherlock question) takes the next slot that frees up,
 * whatever the number of background callers (indexing) waiting: its wait is bounded by the
 * calls already running, not by the length of the queue.</p>
 *
 * <p>Priority is not absolute. After {@link #MAX_PRIORITY_STREAK} slots in a row have gone to
 * priority callers while a background caller was waiting, the next slot goes to the background.
 * A flood of searches — a script, a malicious user — therefore slows indexing down to at least
 * one call in {@code MAX_PRIORITY_STREAK + 1} but can never stall it. Ordinary search traffic
 * never reaches the streak.</p>
 */
final class EmbeddingGate {

    static final int MAX_PRIORITY_STREAK = 4;

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition changed = lock.newCondition();
    private int available;
    private int waitingPriority;
    private int waitingBackground;
    /** Slots given in a row to priority callers while a background caller was waiting. */
    private int priorityStreak;

    EmbeddingGate(int permits) {
        this.available = permits;
    }

    /**
     * Background caller: waits for a free slot that no priority caller is waiting for, or for
     * its turn once priority callers have had {@link #MAX_PRIORITY_STREAK} slots in a row.
     */
    void acquire() throws InterruptedException {
        lock.lock();
        try {
            waitingBackground++;
            try {
                while (available == 0 || (waitingPriority > 0 && !backgroundTurn())) changed.await();
                available--;
                priorityStreak = 0;
                changed.signalAll(); // priority callers held back by the background turn may go
            } finally {
                waitingBackground--;
            }
        } finally {
            lock.unlock();
        }
    }

    private boolean backgroundTurn() {
        return priorityStreak >= MAX_PRIORITY_STREAK;
    }

    /** Priority caller: takes the next free slot, ahead of any waiting background caller. */
    void acquirePriority() throws InterruptedException {
        acquirePriority(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
    }

    /**
     * Priority caller with a deadline.
     *
     * @return true if a slot was taken, false if none freed up in time
     */
    boolean acquirePriority(long timeout, TimeUnit unit) throws InterruptedException {
        long nanos = unit.toNanos(timeout);
        lock.lock();
        try {
            waitingPriority++;
            try {
                while (available == 0 || (waitingBackground > 0 && backgroundTurn())) {
                    if (nanos <= 0) return false;
                    nanos = changed.awaitNanos(nanos);
                }
                available--;
                if (waitingBackground > 0) {
                    priorityStreak++;
                    if (backgroundTurn()) changed.signalAll(); // a free slot may now be theirs
                } else {
                    priorityStreak = 0;
                }
                return true;
            } finally {
                // The last priority caller to leave unblocks the background callers.
                if (--waitingPriority == 0) changed.signalAll();
            }
        } finally {
            lock.unlock();
        }
    }

    void release() {
        lock.lock();
        try {
            available++;
            changed.signalAll();
        } finally {
            lock.unlock();
        }
    }
}
