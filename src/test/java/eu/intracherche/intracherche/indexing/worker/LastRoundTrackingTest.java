package eu.intracherche.intracherche.indexing.worker;

import eu.intracherche.intracherche.indexing.error.IndexingErrorDao;
import eu.intracherche.intracherche.registry.RegistrySchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The admin panel's pages/min come from the last round that processed files. A round runs
 * one cycle per source directory, and the cycles after the first usually find nothing to
 * do: they, and the idle rounds that follow, must not wipe those figures — nor a restart.
 * Each index is timed on its own, and OCR time is wall-clock time, whatever the number of
 * OCR workers.
 */
class LastRoundTrackingTest {

    private static final String CORE = "Index_smb_Tuto";
    private static final String OTHER = "Index_smb_Archives";

    private Connection conn;
    private MutableClock clock;
    private IndexingProgressTracker tracker;

    @BeforeEach
    void setUp() throws Exception {
        conn = DriverManager.getConnection("jdbc:sqlite::memory:");
        conn.setAutoCommit(false);
        RegistrySchema.ensureSchema(conn);
        clock = new MutableClock();
        tracker = newTracker();
    }

    @AfterEach
    void tearDown() throws Exception {
        conn.close();
    }

    private IndexingProgressTracker newTracker() {
        return new IndexingProgressTracker(new IndexingErrorDao(conn), new IndexingRoundDao(conn), clock);
    }

    /** A 12-page text file (1 min), then a 3-page scan OCRed in 9 s, then an idle cycle. */
    private void roundWithWorkThenIdleCycle() {
        tracker.startRound();
        tracker.startCycle(2, List.of(CORE));
        tracker.startFile("/a.docx", List.of(CORE));
        clock.advance(60_000);
        tracker.recordTextPages(12, CORE);
        tracker.recordSuccess(CORE, "/a.docx");
        tracker.fileCompleted("/a.docx");
        tracker.startFile("/b.pdf", List.of(CORE));
        for (int i = 0; i < 3; i++) {
            tracker.ocrPageStarted(CORE);
            clock.advance(3_000);
            tracker.ocrPageFinished(CORE);
            tracker.recordOcrPage(CORE);
        }
        tracker.recordSuccess(CORE, "/b.pdf");
        tracker.fileCompleted("/b.pdf");
        tracker.endCycle();

        tracker.startCycle(0, List.of(CORE));
        tracker.endCycle();
        tracker.endRound();
    }

    @Test
    void no_round_is_shown_before_the_first_one_that_processed_files() {
        tracker.startRound();
        tracker.startCycle(0, List.of(CORE));
        tracker.endCycle();
        tracker.endRound();

        assertNull(tracker.getSnapshot().lastRound());
    }

    @Test
    void an_idle_cycle_in_the_same_round_keeps_the_figures_of_the_one_that_did_the_work() {
        roundWithWorkThenIdleCycle();

        IndexingProgressTracker.LastRound round = tracker.getSnapshot().lastRound();
        assertNotNull(round);
        assertEquals(2, round.files());
        assertEquals(12, round.textPages());
        assertEquals(3, round.ocrPages());
        assertEquals(69_000, round.durationMs());
        assertEquals(9_000, round.ocrActiveMs());
        assertEquals(2, round.indexes().get(CORE).succeeded());
        assertEquals(12, round.indexes().get(CORE).textPages());
        assertEquals(69_000, round.indexes().get(CORE).durationMs());
    }

    @Test
    void an_idle_round_keeps_the_last_round_that_processed_files() {
        roundWithWorkThenIdleCycle();

        tracker.startRound();
        tracker.startCycle(0, List.of(CORE));
        tracker.endCycle();
        tracker.endRound();

        assertEquals(12, tracker.getSnapshot().lastRound().textPages());
    }

    @Test
    void the_last_round_survives_a_restart() {
        roundWithWorkThenIdleCycle();

        IndexingProgressTracker.LastRound round = newTracker().getSnapshot().lastRound();
        assertNotNull(round);
        assertEquals(12, round.textPages());
        assertEquals(3, round.ocrPages());
        assertEquals(1, round.indexes().size());
        assertEquals(3, round.indexes().get(CORE).ocrPages());
        assertEquals(69_000, round.indexes().get(CORE).durationMs());
        assertEquals(9_000, round.indexes().get(CORE).ocrActiveMs());
    }

    /** endCycle() with no cycle open — the file lookup failed — must not count the previous cycle again. */
    @Test
    void a_cycle_is_counted_once() {
        tracker.startRound();
        tracker.startCycle(1, List.of(CORE));
        tracker.startFile("/a.docx", List.of(CORE));
        tracker.recordTextPages(5, CORE);
        tracker.fileCompleted("/a.docx");
        tracker.endCycle();
        tracker.endCycle();
        tracker.endRound();

        assertEquals(5, tracker.getSnapshot().lastRound().textPages());
    }

    /** Two indexes indexed one after the other: each is timed on its own, not on the whole round. */
    @Test
    void indexes_processed_one_after_the_other_each_get_their_own_time() {
        tracker.startRound();
        tracker.startCycle(1, List.of(CORE, OTHER));
        tracker.startFile("/a.docx", List.of(CORE));
        clock.advance(60_000);
        tracker.fileCompleted("/a.docx");
        tracker.endCycle();

        clock.advance(30_000); // discovery of the next source
        tracker.startCycle(1, List.of(CORE, OTHER));
        tracker.startFile("/b.docx", List.of(OTHER));
        clock.advance(120_000);
        tracker.fileCompleted("/b.docx");
        tracker.endCycle();
        tracker.endRound();

        IndexingProgressTracker.LastRound round = tracker.getSnapshot().lastRound();
        assertEquals(60_000, round.indexes().get(CORE).durationMs());
        assertEquals(120_000, round.indexes().get(OTHER).durationMs());
        assertEquals(180_000, round.durationMs(), "the round counts the cycles, not discovery");
    }

    /** Two OCR workers reading pages at the same time: the overlap counts once. */
    @Test
    void ocr_time_is_wall_clock_time_whatever_the_number_of_workers() {
        tracker.startRound();
        tracker.startCycle(1, List.of(CORE, OTHER));
        tracker.startFile("/scan.pdf", List.of(CORE));
        tracker.ocrPageStarted(CORE);   // worker 1
        clock.advance(2_000);
        tracker.ocrPageStarted(OTHER);  // worker 2, alongside
        clock.advance(2_000);
        tracker.ocrPageFinished(CORE);
        clock.advance(2_000);
        tracker.ocrPageFinished(OTHER);
        clock.advance(5_000);           // no OCR in flight: not counted
        tracker.fileCompleted("/scan.pdf");
        tracker.endCycle();
        tracker.endRound();

        IndexingProgressTracker.LastRound round = tracker.getSnapshot().lastRound();
        assertEquals(6_000, round.ocrActiveMs());
        assertEquals(4_000, round.indexes().get(CORE).ocrActiveMs());
        assertEquals(4_000, round.indexes().get(OTHER).ocrActiveMs());
    }

    private static final class MutableClock extends Clock {
        private long millis = Instant.parse("2026-09-26T08:00:00Z").toEpochMilli();

        void advance(long ms) { millis += ms; }

        @Override public long millis() { return millis; }
        @Override public Instant instant() { return Instant.ofEpochMilli(millis); }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
    }
}
