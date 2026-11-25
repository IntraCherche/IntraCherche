package eu.intracherche.intracherche.web;

import eu.intracherche.intracherche.app.ApplicationOrchestrator;
import eu.intracherche.intracherche.audit.AuditService;
import eu.intracherche.intracherche.config.*;
import eu.intracherche.intracherche.indexing.outage.ServiceOutageMonitor;
import eu.intracherche.intracherche.indexing.worker.IndexWorkerManager;
import eu.intracherche.intracherche.indexing.worker.IndexingProgressTracker;
import eu.intracherche.intracherche.process.PythonServiceManager;
import eu.intracherche.intracherche.process.ResourceValidator;
import eu.intracherche.intracherche.process.SolrProcessManager;
import eu.intracherche.intracherche.registry.FileRegistry;
import eu.intracherche.intracherche.services.EmbeddingService;
import eu.intracherche.intracherche.services.FileIndexingService;
import eu.intracherche.intracherche.services.NerService;
import eu.intracherche.intracherche.services.OcrService;
import eu.intracherche.intracherche.services.RerankerService;
import eu.intracherche.intracherche.sherlock.LlmService;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.common.util.SimpleOrderedMap;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A core is only created when the first document reaches it, so a server that has never
 * indexed anything — a fresh install, or a share it cannot reach — holds no core at all.
 * The panel's index table must still list what is configured, or it disappears entirely
 * and the file count looks like a lost feature.
 */
class AdminHealthCoresTest {

    @Test
    void a_configured_index_with_no_core_is_still_listed_with_zero_files() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controllerWith(index("Index_smb_Tuto", Status.ACTIVE, false)))
                .build();

        mvc.perform(get("/api/admin/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.solr.cores.length()").value(1))
                .andExpect(jsonPath("$.solr.cores[0].name").value("Index_smb_Tuto"))
                .andExpect(jsonPath("$.solr.cores[0].numFiles").value(0))
                .andExpect(jsonPath("$.solr.cores[0].created").value(false))
                .andExpect(jsonPath("$.solr.cores[0].sizeInBytes").doesNotExist())
                .andExpect(jsonPath("$.solr.cores[0].lastModified").doesNotExist());
    }

    /** An index being deleted is on its way out; it is not a row an admin should count on. */
    @Test
    void an_index_confirmed_for_deletion_is_left_out() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controllerWith(
                        index("Index_Kept", Status.ACTIVE, false),
                        index("Index_Going", Status.DELETE, true),
                        index("Index_Unconfirmed", Status.DELETE, false)))
                .build();

        mvc.perform(get("/api/admin/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.solr.cores.length()").value(2))
                .andExpect(jsonPath("$.solr.cores[0].name").value("Index_Kept"))
                .andExpect(jsonPath("$.solr.cores[1].name").value("Index_Unconfirmed"));
    }

    // ── Fixtures ─────────────────────────────────────────────────────────────

    private static IndexConfig index(String coreName, Status status, boolean confirmDelete) {
        IndexConfig config = new IndexConfig();
        config.setCoreName(coreName);
        config.setStatus(status);
        config.setConfirmDelete(confirmDelete);
        config.setIndexedDirs(List.of());
        return config;
    }

    /** An AdminController whose Solr answers the core STATUS request with no core at all. */
    private static AdminController controllerWith(IndexConfig... indexes) throws Exception {
        SolrClient solrClient = mock(SolrClient.class);
        NamedList<Object> coreAdminResponse = new SimpleOrderedMap<>();
        coreAdminResponse.add("status", new SimpleOrderedMap<>());
        when(solrClient.request(any(), any())).thenReturn(coreAdminResponse);

        IndexesConfig indexesConfig = new IndexesConfig();
        indexesConfig.setIndexes(List.of(indexes));

        AdminConfig adminConfig = mock(AdminConfig.class);
        when(adminConfig.isAdmin(any())).thenReturn(true);

        AdminController controller = new AdminController(
                mock(IndexingProgressTracker.class),
                mock(IndexWorkerManager.class),
                mock(FileRegistry.class),
                adminConfig,
                mock(ApplicationOrchestrator.class),
                mock(EmbeddingService.class),
                mock(NerService.class),
                mock(OcrService.class),
                mock(RerankerService.class),
                mock(EmbeddingConfig.class),
                mock(NerConfig.class),
                mock(OcrConfig.class),
                mock(RerankerConfig.class),
                mock(LlmService.class),
                mock(LlmConfig.class),
                solrClient,
                new SolrConfig("http://localhost:8983/solr", "solr/configset"),
                indexesConfig,
                mock(PythonServiceManager.class),
                mock(SolrProcessManager.class),
                mock(ResourceValidator.class),
                mock(FileIndexingService.class),
                mock(AuditService.class),
                mock(ServiceOutageMonitor.class));
        ReflectionTestUtils.setField(controller, "appVersion", "3.1.0");
        return controller;
    }
}
