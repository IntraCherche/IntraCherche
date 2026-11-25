package eu.intracherche.intracherche.sherlock;

import eu.intracherche.intracherche.sherlock.SherlockRetriever.DocumentPlan;
import eu.intracherche.intracherche.sherlock.SherlockRetriever.DocumentReading;
import eu.intracherche.intracherche.sherlock.SherlockRetriever.Settings;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SherlockDocumentNoticeTest {

    private static final Settings CPU = new Settings(40, 5, true, false);
    private static final Settings GPU = new Settings(100, 5, true, true);

    @Test
    void a_document_read_whole_at_the_usual_speed_says_nothing() {
        assertThat(SherlockWorker.notice(new DocumentPlan(12, 30, DocumentReading.WHOLE), CPU)).isNull();
    }

    @Test
    void a_long_document_read_on_the_cpu_warns_that_it_is_slow() {
        assertThat(SherlockWorker.notice(new DocumentPlan(120, 260, DocumentReading.IN_BATCHES), CPU))
                .contains("120 pages").contains("sans carte graphique");
    }

    @Test
    void on_a_gpu_reading_in_batches_is_fast_and_says_nothing() {
        assertThat(SherlockWorker.notice(new DocumentPlan(120, 260, DocumentReading.IN_BATCHES), GPU)).isNull();
    }

    @Test
    void a_document_read_in_part_says_the_answer_may_be_incomplete_even_on_a_gpu() {
        assertThat(SherlockWorker.notice(new DocumentPlan(900, -1, DocumentReading.PARTIAL), GPU))
                .contains("900 pages").contains("incomplète");
    }
}
