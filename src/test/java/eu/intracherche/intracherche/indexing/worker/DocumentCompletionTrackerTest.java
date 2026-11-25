package eu.intracherche.intracherche.indexing.worker;

import eu.intracherche.intracherche.indexing.error.IndexingErrorDao;
import eu.intracherche.intracherche.registry.FileRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class DocumentCompletionTrackerTest {

    private FileRegistry fileRegistry;
    private IndexingProgressTracker progressTracker;
    private DocumentCompletionTracker tracker;

    @BeforeEach
    void setUp() {
        fileRegistry = mock(FileRegistry.class);
        progressTracker = new IndexingProgressTracker(mock(IndexingErrorDao.class), mock(IndexingRoundDao.class));
        progressTracker.startCycle(1, List.of("core"));
        tracker = new DocumentCompletionTracker(fileRegistry, progressTracker);
    }

    // A text document is marked indexed by the tracker once Solr has it, not when its
    // tasks are enqueued — and its writes are not scanned pages.
    @Test
    void textDocumentIsMarkedIndexedOnlyAfterEveryWrite() throws Exception {
        tracker.expectWrites("text-doc", 2);

        tracker.solrPageDone("text-doc", true, false);
        verify(fileRegistry, never()).markIndexed(anyString());

        tracker.solrPageDone("text-doc", true, false);
        verify(fileRegistry).markIndexed("text-doc");
        assertThat(progressTracker.getSnapshot().cycleOcrPages()).isZero();
        assertThat(progressTracker.getSnapshot().ocrPagesTotal()).isZero();
    }

    // A core that refused the write is missing the file: it must stay pending.
    @Test
    void textDocumentWithARejectedWriteStaysPending() throws Exception {
        tracker.expectWrites("text-doc", 2);

        tracker.solrPageDone("text-doc", true, false);
        tracker.solrPageDone("text-doc", false, false);

        verify(fileRegistry, never()).markIndexed(anyString());
    }

    // A scanned page the embedding service could not embed was never sent: even with its
    // other pages written, the document stays pending for the next cycle.
    @Test
    void scannedDocumentWithAnUnembeddedPageStaysPending() throws Exception {
        tracker.register("scan-doc", 2, "core", "scan.pdf");

        tracker.solrPageDone("scan-doc", true, true);
        tracker.ocrPageMissing("scan-doc");

        verify(fileRegistry, never()).markIndexed(anyString());
    }

    // An empty OCR page has nothing to index: the document is still marked indexed.
    @Test
    void scannedDocumentWithAnEmptyPageIsMarkedIndexed() throws Exception {
        tracker.register("scan-doc", 2, "core", "scan.pdf");

        tracker.solrPageDone("scan-doc", true, true);
        tracker.ocrPageFailed("scan-doc");

        verify(fileRegistry).markIndexed("scan-doc");
    }

    // A write for a document the tracker does not know (cancelled by a pause) is ignored.
    @Test
    void unknownDocumentIsIgnored() throws Exception {
        tracker.solrPageDone("unknown", true, false);

        verify(fileRegistry, never()).markIndexed(anyString());
        assertThat(progressTracker.getSnapshot().ocrPagesTotal()).isZero();
    }

    @Test
    void scannedDocumentPagesAreCountedAndMarkIndexedOnLastPage() throws Exception {
        tracker.register("scan-doc", 2, "core", "scan.pdf");

        tracker.solrPageDone("scan-doc", true, true);
        verify(fileRegistry, never()).markIndexed(anyString());

        tracker.solrPageDone("scan-doc", true, true);
        assertThat(progressTracker.getSnapshot().cycleOcrPages()).isEqualTo(2);
        verify(fileRegistry).markIndexed("scan-doc");
    }

    // A PDF mixing text and scanned pages waits for both its OCR pages and its text write;
    // before, the text write was taken for one of the OCR pages and finished it early.
    @Test
    void mixedDocumentWaitsForOcrPagesAndTextWrite() throws Exception {
        tracker.register("mixed-doc", 1, "core", "mixed.pdf");
        tracker.expectWrites("mixed-doc", 1);

        tracker.solrPageDone("mixed-doc", true, false);
        verify(fileRegistry, never()).markIndexed(anyString());

        tracker.solrPageDone("mixed-doc", true, true);
        verify(fileRegistry).markIndexed("mixed-doc");
        assertThat(progressTracker.getSnapshot().cycleOcrPages()).isEqualTo(1);
    }

    @Test
    void waitForCompletionReturnsOnceTextWritesAreDone() throws Exception {
        tracker.expectWrites("text-doc", 1);
        tracker.solrPageDone("text-doc", true, false);

        long start = System.currentTimeMillis();
        tracker.waitForCompletion(5);
        assertThat(System.currentTimeMillis() - start).isLessThan(1000);
    }
}
