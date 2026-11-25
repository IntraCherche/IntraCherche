package eu.intracherche.intracherche.services;

import com.hierynomus.smbj.share.DiskShare;
import eu.intracherche.intracherche.common.states.IndexingState;
import eu.intracherche.intracherche.config.IndexesConfig;
import eu.intracherche.intracherche.discovery.SmbClientManager;
import eu.intracherche.intracherche.indexing.worker.IndexingProgressTracker;
import eu.intracherche.intracherche.registry.FileRecord;
import eu.intracherche.intracherche.registry.FileRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Nov. 2026: a few huge spreadsheets and scanned PDFs exhausted the 4 GB heap again and again.
 * Each time the application came back, it parsed the same files and died the same way. The file
 * being parsed is now recorded, so that after a crash the suspects are parsed alone and a file
 * that brings the application down twice is indexed by its name only.
 */
class FileIndexingCrashTest {

    private static final String HOST = "198.51.100.21";
    private static final String SHARE = "archives";

    private static FileRecord file(String id) {
        return new FileRecord(id, "dir\\" + id + ".xlsx", 0L, 1000L, null,
                IndexingState.DISCOVERED, 0L, true, SHARE, HOST,
                List.of(), List.of("Index_Archives"), null, null, null, null);
    }

    private FileIndexingService service(FileRegistry registry, SmbClientManager smb) {
        IndexesConfig indexesConfig = mock(IndexesConfig.class);
        when(indexesConfig.getIndexes()).thenReturn(List.of());
        return new FileIndexingService(registry, indexesConfig, null, null, smb,
                null, null, null, null, null, null, mock(IndexingProgressTracker.class), null);
    }

    @Test
    void aMemoryExhaustionTheApplicationSurvivesIsCountedAgainstTheFile() throws Exception {
        FileRegistry registry = mock(FileRegistry.class);
        when(registry.findFilesToIndex()).thenReturn(List.of(file("heavy")));
        SmbClientManager smb = mock(SmbClientManager.class);
        when(smb.getShare(HOST, SHARE)).thenThrow(new OutOfMemoryError("Java heap space"));

        FileIndexingService service = service(registry, smb);
        try {
            service.processFilesToIndex();
        } finally {
            service.tearDown();
        }

        verify(registry).recordCrash("heavy");
        // Marked while parsing, and unmarked afterwards so that the next start does not count it twice.
        InOrder order = inOrder(registry);
        order.verify(registry).markAttemptStarted("heavy");
        order.verify(registry).clearAttempt("heavy");
    }

    @Test
    void anOrdinaryFailureIsNotACrash() throws Exception {
        FileRegistry registry = mock(FileRegistry.class);
        when(registry.findFilesToIndex()).thenReturn(List.of(file("corrupt")));
        SmbClientManager smb = mock(SmbClientManager.class);
        when(smb.getShare(HOST, SHARE)).thenThrow(new IllegalStateException("boom"));

        FileIndexingService service = service(registry, smb);
        try {
            service.processFilesToIndex();
        } finally {
            service.tearDown();
        }

        verify(registry, never()).recordCrash(anyString());
        verify(registry).clearAttempt("corrupt");
    }

    @Test
    void filesInFlightAtTheLastCrashAreCountedOnceAtTheFirstCycle() throws Exception {
        FileRegistry registry = mock(FileRegistry.class);
        when(registry.findFilesToIndex()).thenReturn(List.of());
        when(registry.recoverInterruptedAttempts()).thenReturn(List.of("a", "b"));

        FileIndexingService service = service(registry, mock(SmbClientManager.class));
        try {
            service.processFilesToIndex();
            service.processFilesToIndex();
        } finally {
            service.tearDown();
        }

        verify(registry, times(1)).recoverInterruptedAttempts();
    }

    @Test
    void suspectsAreParsedFirstAndOneAtATime() throws Exception {
        FileRegistry registry = mock(FileRegistry.class);
        when(registry.findFilesToIndex()).thenReturn(List.of(file("ok1"), file("suspect"), file("ok2")));
        when(registry.crashSuspectIds()).thenReturn(Set.of("suspect"));
        SmbClientManager smb = mock(SmbClientManager.class);
        when(smb.getShare(any(), any())).thenThrow(new IllegalStateException("not reached for real"));
        // The suspect's marker must be set and cleared before any other file is started.
        InOrder order = inOrder(registry);

        FileIndexingService service = service(registry, smb);
        try {
            service.processFilesToIndex();
        } finally {
            service.tearDown();
        }

        order.verify(registry).markAttemptStarted("suspect");
        order.verify(registry).clearAttempt("suspect");
        order.verify(registry).markAttemptStarted("ok1");
    }
}
