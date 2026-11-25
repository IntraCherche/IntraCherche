package eu.intracherche.intracherche.app;

import eu.intracherche.intracherche.config.IndexConfig;
import eu.intracherche.intracherche.config.IndexesConfig;
import eu.intracherche.intracherche.config.Status;
import eu.intracherche.intracherche.services.FileIndexingService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** Every active index gets its Solr core before indexing starts; paused and deleted ones do not. */
class EnsureActiveCoresTest {

    private static IndexConfig index(String core, Status status) {
        IndexConfig c = new IndexConfig();
        c.setCoreName(core);
        c.setStatus(status);
        return c;
    }

    private static ApplicationOrchestrator orchestrator(FileIndexingService svc, IndexConfig... indexes) {
        IndexesConfig cfg = new IndexesConfig();
        cfg.setIndexes(List.of(indexes));
        return new ApplicationOrchestrator(null, svc, null, null, cfg, null, null, null, null, null, null, null, null, null);
    }

    @Test
    void createsCoreOfEveryActiveIndexAndSkipsPausedAndDeleted() {
        FileIndexingService svc = mock(FileIndexingService.class);
        when(svc.createCoreIfNotExists(anyString())).thenReturn(true);

        orchestrator(svc,
                index("Index_Alpha", Status.ACTIVE),
                index("Index_Beta", Status.ACTIVE),
                index("Index_Paused", Status.PAUSED),
                index("Index_Gone", Status.DELETE)).ensureActiveCores();

        verify(svc).createCoreIfNotExists("Index_Alpha");
        verify(svc).createCoreIfNotExists("Index_Beta");
        verify(svc, never()).createCoreIfNotExists("Index_Paused");
        verify(svc, never()).createCoreIfNotExists("Index_Gone");
    }

    @Test
    void aFailureOnOneIndexDoesNotStopTheOthers() {
        FileIndexingService svc = mock(FileIndexingService.class);
        when(svc.createCoreIfNotExists("Index_A")).thenThrow(new IllegalStateException("solr down"));
        when(svc.createCoreIfNotExists("Index_B")).thenReturn(false);
        when(svc.createCoreIfNotExists("Index_C")).thenReturn(true);

        orchestrator(svc,
                index("Index_A", Status.ACTIVE),
                index("Index_B", Status.ACTIVE),
                index("Index_C", Status.ACTIVE)).ensureActiveCores();

        verify(svc).createCoreIfNotExists("Index_C");
    }
}
