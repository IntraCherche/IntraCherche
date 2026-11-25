package eu.intracherche.intracherche.indexing.worker;

import eu.intracherche.intracherche.indexing.error.IndexingErrorDao;
import eu.intracherche.intracherche.registry.RegistrySchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A share that cannot be opened produces no per-file error, so it has to be recorded
 * against the source itself — otherwise the panel shows an empty registry and "tous les
 * fichiers sont à jour" while nothing at all has been read.
 */
class SourceFailureTrackingTest {

    private static final String SOURCE = "\\\\192.0.2.21\\Demo_tuto_Youtube";
    private static final String CORE = "Index_smb_Tuto";

    private Connection conn;
    private IndexingProgressTracker tracker;

    @BeforeEach
    void setUp() throws Exception {
        conn = DriverManager.getConnection("jdbc:sqlite::memory:");
        conn.setAutoCommit(false);
        RegistrySchema.ensureSchema(conn);
        tracker = new IndexingProgressTracker(new IndexingErrorDao(conn), new IndexingRoundDao(conn));
    }

    @AfterEach
    void tearDown() throws Exception {
        conn.close();
    }

    @Test
    void an_unreachable_source_is_recorded_as_its_own_problem() {
        tracker.recordSourceFailure(CORE, SOURCE, "SMB session open failed after 3 attempts");

        List<IndexingErrorDao.Row> rows = tracker.getErrorRows();
        assertEquals(1, rows.size());
        assertEquals("source", rows.get(0).kind());
        assertEquals(SOURCE, rows.get(0).path());
        assertEquals(CORE, rows.get(0).coreName());
        assertTrue(rows.get(0).message().startsWith("SMB session open failed"));
    }

    /** A cycle a minute against a dead share must not bury the files' own errors. */
    @Test
    void repeated_failures_of_the_same_source_keep_a_single_row() {
        tracker.recordSourceFailure(CORE, SOURCE, "Le réseau n'est pas accessible");
        tracker.recordSourceFailure(CORE, SOURCE, "Le réseau n'est pas accessible");
        tracker.recordSourceFailure(CORE, SOURCE, "Connection refused");

        List<IndexingErrorDao.Row> rows = tracker.getErrorRows();
        assertEquals(1, rows.size());
        assertEquals("Connection refused", rows.get(0).message(), "the row carries the latest attempt");
    }

    @Test
    void a_source_read_again_clears_its_problem() {
        tracker.recordSourceFailure(CORE, SOURCE, "Le réseau n'est pas accessible");
        tracker.clearSourceFailure(CORE, SOURCE);

        assertEquals(List.of(), tracker.getErrorRows());
    }

    /** Two indexes reading the same share each keep their own row. */
    @Test
    void two_indexes_on_the_same_share_are_reported_separately() {
        tracker.recordSourceFailure(CORE, SOURCE, "Le réseau n'est pas accessible");
        tracker.recordSourceFailure("Index_smb_Archives", SOURCE, "Le réseau n'est pas accessible");

        assertEquals(2, tracker.getErrorRows().size());

        tracker.clearSourceFailure(CORE, SOURCE);

        List<IndexingErrorDao.Row> rows = tracker.getErrorRows();
        assertEquals(1, rows.size());
        assertEquals("Index_smb_Archives", rows.get(0).coreName());
    }

    /**
     * The address of a share that was never reachable gets corrected; nothing reads the
     * old address again, so only the start-of-cycle prune can clear what it left.
     */
    @Test
    void a_source_the_configuration_no_longer_names_is_dropped() {
        tracker.recordSourceFailure(CORE, SOURCE, "Le réseau n'est pas accessible");

        tracker.pruneSourceFailures(List.of(
                new IndexingErrorDao.Source(CORE, "\\\\198.51.100.21\\Demo_tuto_Youtube")));

        assertEquals(List.of(), tracker.getErrorRows());
    }

    /** The prune keeps the problems of the sources still configured, and the file errors. */
    @Test
    void pruning_keeps_configured_sources_and_file_errors() {
        tracker.recordSourceFailure(CORE, SOURCE, "Le réseau n'est pas accessible");
        tracker.recordSourceFailure("Index_smb_Archives", "\\\\srv\\Archives", "Access denied");
        tracker.recordFailure(CORE, SOURCE + "\\rapport.pdf", "OCR timeout");

        tracker.pruneSourceFailures(List.of(new IndexingErrorDao.Source(CORE, SOURCE)));

        List<IndexingErrorDao.Row> rows = tracker.getErrorRows();
        assertEquals(2, rows.size());
        assertTrue(rows.stream().anyMatch(r -> "source".equals(r.kind()) && CORE.equals(r.coreName())));
        assertTrue(rows.stream().anyMatch(r -> "error".equals(r.kind())));
    }

    /** Nothing configured at all: no source problem can still be true. */
    @Test
    void pruning_with_no_configured_source_drops_every_source_row() {
        tracker.recordSourceFailure(CORE, SOURCE, "Le réseau n'est pas accessible");

        tracker.pruneSourceFailures(List.of());

        assertEquals(List.of(), tracker.getErrorRows());
    }

    /** Clearing a source leaves the failures of the files it holds alone. */
    @Test
    void clearing_a_source_leaves_file_errors_untouched() {
        tracker.recordFailure(CORE, SOURCE + "\\rapport.pdf", "OCR timeout");
        tracker.recordSourceFailure(CORE, SOURCE, "Le réseau n'est pas accessible");
        tracker.clearSourceFailure(CORE, SOURCE);

        List<IndexingErrorDao.Row> rows = tracker.getErrorRows();
        assertEquals(1, rows.size());
        assertEquals("error", rows.get(0).kind());
        assertEquals(SOURCE + "\\rapport.pdf", rows.get(0).path());
    }

    /**
     * A source that was read but could not be recorded in the registry is not unreachable:
     * it is listed with the file errors, and counts as no failed file.
     */
    @Test
    void a_feeding_failure_is_a_file_error_not_a_source_problem() {
        tracker.recordFeedingFailure(CORE, SOURCE, "Enregistrement des fichiers découverts bloqué");

        List<IndexingErrorDao.Row> rows = tracker.getErrorRows();
        assertEquals(1, rows.size());
        assertEquals("error", rows.get(0).kind());
        assertEquals(SOURCE, rows.get(0).path());
        assertTrue(tracker.getIndexSummaries().isEmpty(), "no file is counted as failed");
    }
}
