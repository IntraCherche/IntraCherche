package eu.intracherche.intracherche.indexing.worker;

import eu.intracherche.intracherche.indexing.model.IndexTask;
import eu.intracherche.intracherche.indexing.model.OCRTask;
import eu.intracherche.intracherche.indexing.outage.ServiceOutageMonitor;
import eu.intracherche.intracherche.indexing.outage.ServiceOutageMonitor.Dependency;
import eu.intracherche.intracherche.indexing.outage.ServiceUnavailableException;
import eu.intracherche.intracherche.queues.IndexTaskQueue;
import eu.intracherche.intracherche.queues.OcrQueue;
import eu.intracherche.intracherche.services.EmbeddingService;
import eu.intracherche.intracherche.services.OcrService;
import org.apache.tika.metadata.Metadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Scanned pages skip the enrichment pipeline, so OcrWorker must embed them itself — otherwise
 * they never reach the kNN leg of hybrid search.
 */
class OcrWorkerEmbeddingTest {

    private static final String OCR_TEXT = "Remplacement des automates de gestion de regulations a la piscine";

    private OcrService ocrService;
    private EmbeddingService embeddingService;
    private IndexTaskQueue indexTaskQueue;
    private DocumentCompletionTracker completionTracker;
    private OcrWorker worker;

    @BeforeEach
    void setUp() {
        ocrService = mock(OcrService.class);
        embeddingService = mock(EmbeddingService.class);
        indexTaskQueue = new IndexTaskQueue(10);
        completionTracker = mock(DocumentCompletionTracker.class);
        worker = new OcrWorker("ocr-test", ocrService, embeddingService, new OcrQueue(10),
                indexTaskQueue, completionTracker);
        when(ocrService.extractTextFromImage(any())).thenReturn(OCR_TEXT);
    }

    private IndexTask process() throws InterruptedException {
        worker.processOcrTask(OCRTask.of("doc1", "Index_Test", "CR/PV.pdf", new byte[]{1}, 2,
                new Metadata(), Set.of("S-1"), Set.of(), 0L, null, null));
        return indexTaskQueue.take();
    }

    @Test
    void ocr_page_is_indexed_with_its_passage_embedding() throws Exception {
        float[] vector = {0.1f, 0.2f, 0.3f};
        when(embeddingService.embed(OCR_TEXT, false)).thenReturn(vector);

        IndexTask task = process();

        assertThat(task.sectionVectors()).containsExactly(vector);
        assertThat(new IndexWorker("w", null, 0).convertToSolrDocuments(task).getFirst()
                .getFieldValues("content_vector")).hasSize(3);
    }

    // Indexed without its vector, the page would be marked indexed and stay out of the kNN
    // leg for good (embedding service down or still starting): the document stays pending.
    @Test
    void failed_embedding_leaves_the_document_pending() throws Exception {
        when(embeddingService.embed(any(), anyBoolean())).thenReturn(null);
        when(embeddingService.isEnabled()).thenReturn(true);

        worker.processOcrTask(OCRTask.of("doc1", "Index_Test", "CR/PV.pdf", new byte[]{1}, 2,
                new Metadata(), Set.of("S-1"), Set.of(), 0L, null, null));

        assertThat(indexTaskQueue.size()).isZero();
        verify(completionTracker).ocrPageMissing("doc1");
        verify(completionTracker, never()).ocrPageFailed(any());
    }

    // Embedding switched off: the page is indexed for BM25 only, as before.
    @Test
    void disabled_embedding_indexes_the_page_without_vector() throws Exception {
        when(embeddingService.embed(any(), anyBoolean())).thenReturn(null);
        when(embeddingService.isEnabled()).thenReturn(false);

        IndexTask task = process();

        assertThat(task.sections()).hasSize(1);
        assertThat(new IndexWorker("w", null, 0).convertToSolrDocuments(task).getFirst()
                .getFieldValues("content_vector")).isNull();
    }

    private OcrWorker workerWith(ServiceOutageMonitor monitor) {
        return new OcrWorker("ocr-test", ocrService, embeddingService, new OcrQueue(10),
                indexTaskQueue, completionTracker, monitor);
    }

    private void processWith(OcrWorker w) {
        w.processOcrTask(OCRTask.of("doc1", "Index_Test", "CR/PV.pdf", new byte[]{1}, 2,
                new Metadata(), Set.of("S-1"), Set.of(), 0L, null, null));
    }

    // Embedding down while the page is in hand: the worker waits, then embeds and sends it —
    // the page is not OCRed a second time.
    @Test
    void embedding_outage_waits_then_indexes_the_page() throws Exception {
        ServiceOutageMonitor monitor = mock(ServiceOutageMonitor.class);
        when(monitor.confirmOutage(Dependency.EMBEDDING)).thenReturn(true);
        float[] vector = {0.1f, 0.2f};
        when(embeddingService.isEnabled()).thenReturn(true);
        when(embeddingService.embed(OCR_TEXT, false)).thenReturn(null).thenReturn(vector);

        processWith(workerWith(monitor));

        verify(monitor).awaitRecovery(eq(Dependency.EMBEDDING), any());
        verify(ocrService, times(1)).extractTextFromImage(any());
        assertThat(indexTaskQueue.take().sectionVectors()).containsExactly(vector);
    }

    // OCR down: the worker waits and reads the page once the service is back.
    @Test
    void ocr_outage_waits_then_reads_the_page() throws Exception {
        ServiceOutageMonitor monitor = mock(ServiceOutageMonitor.class);
        when(monitor.confirmOutage(Dependency.OCR)).thenReturn(true);
        when(ocrService.extractTextFromImage(any()))
                .thenThrow(new ResourceAccessException("Connection refused"))
                .thenReturn(OCR_TEXT);
        when(embeddingService.embed(OCR_TEXT, false)).thenReturn(new float[]{0.3f});

        processWith(workerWith(monitor));

        verify(monitor).awaitRecovery(eq(Dependency.OCR), any());
        assertThat(indexTaskQueue.take().sections().getFirst().content()).isEqualTo(OCR_TEXT);
        verify(completionTracker, never()).ocrPageFailed(any());
    }

    // The OCR service answers but fails on this page: counted as done, as before, so that an
    // unreadable page does not have the whole document OCRed again every cycle.
    @Test
    void ocr_failure_while_the_service_answers_drops_the_page() {
        ServiceOutageMonitor monitor = mock(ServiceOutageMonitor.class);
        when(monitor.confirmOutage(Dependency.OCR)).thenReturn(false);
        when(ocrService.extractTextFromImage(any())).thenThrow(new ResourceAccessException("500"));

        processWith(workerWith(monitor));

        assertThat(indexTaskQueue.size()).isZero();
        verify(completionTracker).ocrPageFailed("doc1");
    }

    // Worker stopped while waiting: the page is missing, so the document stays pending.
    @Test
    void stopped_while_waiting_leaves_the_document_pending() throws Exception {
        ServiceOutageMonitor monitor = mock(ServiceOutageMonitor.class);
        when(monitor.confirmOutage(Dependency.OCR)).thenReturn(true);
        doThrow(new ServiceUnavailableException(Dependency.OCR)).when(monitor).awaitRecovery(eq(Dependency.OCR), any());
        when(ocrService.extractTextFromImage(any())).thenThrow(new ResourceAccessException("Connection refused"));

        processWith(workerWith(monitor));

        verify(completionTracker).ocrPageMissing("doc1");
        verify(completionTracker, never()).ocrPageFailed(any());
    }
}
