package eu.intracherche.intracherche.sherlock;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** llm.queueCapacity: past it, a new question is refused at once rather than queued. */
class SherlockQueueCapacityTest {

    @Test
    void questions_past_the_capacity_are_refused() throws Exception {
        SherlockQueue queue = new SherlockQueue(2);

        assertThat(queue.publish(question("1"))).isTrue();
        assertThat(queue.publish(question("2"))).isTrue();
        assertThat(queue.publish(question("3"))).isFalse();
        assertThat(queue.size()).isEqualTo(2);
    }

    private static SherlockQuestion question(String id) {
        return new SherlockQuestion(id, "alice", "", "Question " + id, SherlockQuestionStatus.QUEUED,
                List.of(), null, List.of(), System.currentTimeMillis(), null, null, List.of(), List.of(), null, null, java.util.Map.of(), null);
    }
}
