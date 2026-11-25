package eu.intracherche.intracherche.services;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class EmbeddingGateTest {

    /** Starts a thread that takes a slot, records its name, and releases the slot. */
    private static Thread taker(EmbeddingGate gate, String name, boolean priority, List<String> order) {
        Thread t = new Thread(() -> {
            try {
                if (priority) gate.acquirePriority(); else gate.acquire();
                order.add(name);
                gate.release();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, name);
        t.start();
        return t;
    }

    private static void awaitWaiting(Thread t) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (t.getState() != Thread.State.WAITING && t.getState() != Thread.State.TIMED_WAITING) {
            if (System.nanoTime() > deadline) throw new AssertionError(t.getName() + " never blocked");
            Thread.sleep(5);
        }
    }

    @Test
    void priority_caller_overtakes_background_callers_queued_before_it() throws Exception {
        EmbeddingGate gate = new EmbeddingGate(1);
        List<String> order = new CopyOnWriteArrayList<>();
        gate.acquire(); // an indexing call in flight

        Thread indexing1 = taker(gate, "indexing-1", false, order);
        Thread indexing2 = taker(gate, "indexing-2", false, order);
        awaitWaiting(indexing1);
        awaitWaiting(indexing2);
        Thread search = taker(gate, "search", true, order);
        awaitWaiting(search);

        gate.release();
        for (Thread t : List.of(indexing1, indexing2, search)) t.join(5000);

        assertThat(order).first().isEqualTo("search");
        assertThat(order).containsExactlyInAnyOrder("search", "indexing-1", "indexing-2");
    }

    @Test
    void a_flood_of_priority_callers_cannot_stall_the_background() throws Exception {
        EmbeddingGate gate = new EmbeddingGate(1);
        List<String> order = new CopyOnWriteArrayList<>();
        gate.acquire();

        Thread indexing = taker(gate, "indexing", false, order);
        awaitWaiting(indexing);
        List<Thread> searches = new java.util.ArrayList<>();
        for (int i = 0; i < EmbeddingGate.MAX_PRIORITY_STREAK + 3; i++) {
            Thread search = taker(gate, "search", true, order);
            awaitWaiting(search);
            searches.add(search);
        }

        gate.release();
        indexing.join(5000);
        for (Thread t : searches) t.join(5000);

        // Searches keep their lead, but the waiting indexing call gets the slot after the streak
        assertThat(order).hasSize(EmbeddingGate.MAX_PRIORITY_STREAK + 4);
        assertThat(order.indexOf("indexing")).isEqualTo(EmbeddingGate.MAX_PRIORITY_STREAK);
    }

    @Test
    void priority_caller_gives_up_at_its_deadline_and_background_resumes() throws Exception {
        EmbeddingGate gate = new EmbeddingGate(1);
        List<String> order = new CopyOnWriteArrayList<>();
        gate.acquire();
        Thread indexing = taker(gate, "indexing", false, order);
        awaitWaiting(indexing);

        long start = System.nanoTime();
        assertThat(gate.acquirePriority(200, TimeUnit.MILLISECONDS)).isFalse();
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isGreaterThanOrEqualTo(150);

        // A priority caller that left must not keep background callers blocked
        gate.release();
        indexing.join(5000);
        assertThat(order).containsExactly("indexing");
    }

    @Test
    void free_slot_is_taken_at_once_by_either_kind() throws Exception {
        EmbeddingGate gate = new EmbeddingGate(2);
        assertThat(gate.acquirePriority(0, TimeUnit.MILLISECONDS)).isTrue();
        gate.acquire();
        assertThat(gate.acquirePriority(0, TimeUnit.MILLISECONDS)).isFalse();
    }
}
