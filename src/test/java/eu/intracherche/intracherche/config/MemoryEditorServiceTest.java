package eu.intracherche.intracherche.config;

import eu.intracherche.intracherche.process.ResourceValidator;
import eu.intracherche.intracherche.process.ResourceValidator.ServiceAllocation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class MemoryEditorServiceTest {

    @TempDir Path dir;
    Path app, solrIn;
    ResourceValidator validator = Mockito.mock(ResourceValidator.class);
    MemoryEditorService svc;

    @BeforeEach
    void setUp() throws Exception {
        app = dir.resolve("application.yaml");
        Files.writeString(app, "resources:\n  reservedRamGb: 3\n");
        solrIn = dir.resolve("solr.in.sh");
        Files.writeString(solrIn, "#SOLR_JAVA_MEM=\"-Xms512m\"\nSOLR_PORT=8983\n");
        MockEnvironment env = new MockEnvironment().withProperty("spring.config.additional-location", "file:" + app);
        ConfigCheckService checker = new ConfigCheckService(env, new IndexesConfig());
        SettingsEditorService settings = new SettingsEditorService(env, checker, null, Mockito.mock(LdapConnectionTester.class));
        Mockito.when(validator.totalRamMb()).thenReturn(16384L);
        Mockito.when(validator.currentHeapMb()).thenReturn(2048L);
        Mockito.when(validator.currentSolrHeapMb()).thenReturn(1024L);
        Mockito.when(validator.preview(Mockito.anyLong(), Mockito.anyLong(), Mockito.anyInt())).thenAnswer(inv -> {
            long heap = inv.getArgument(0), solr = inv.getArgument(1);
            int gb = inv.getArgument(2);
            long llm = 7936, reserved = Math.max(gb * 1024L, heap + solr + 1024 + llm);
            return new ResourceValidator.Preview(16384, heap, solr, 1024, llm, reserved, 16384 - reserved, 3800, 3800, true,
                    List.of(new ServiceAllocation("ocr", "OCR", 1, 1300, true)));
        });
        svc = new MemoryEditorService(validator, checker, settings, solrIn);
    }

    @Test
    void jvmConfKeepsOtherVariablesAndReplacesTheHeapLine() throws Exception {
        Path jvm = dir.resolve("jvm.conf");
        Files.writeString(jvm, "# old comment\nIC_HEAP_MB=2048\nOTHER=1\n");
        svc.writeJvmConf(jvm, 4096);
        String t = Files.readString(jvm);
        assertTrue(t.startsWith("# Auto generated"));
        assertTrue(t.contains("IC_HEAP_MB=4096"));
        assertEquals(1, t.split("IC_HEAP_MB").length - 1);
        assertTrue(t.contains("OTHER=1"));
        assertFalse(t.contains("old comment"));
    }

    @Test
    void solrBlockModeWritesAndRewritesTheOwnBlock() throws Exception {
        svc.writeSolrHeap(2048);
        String t = Files.readString(solrIn);
        assertTrue(t.contains("SOLR_HEAP=\"2048m\""));
        assertTrue(t.contains("SOLR_PORT=8983"));
        svc.writeSolrHeap(3072);
        String t2 = Files.readString(solrIn);
        assertEquals(1, t2.split("SOLR_HEAP=").length - 1);
        assertTrue(t2.contains("3072m"));
    }

    @Test
    void anAdminsOwnSolrHeapLineIsEditedInPlace() throws Exception {
        Files.writeString(solrIn, "SOLR_HEAP=\"1g\"\nSOLR_PORT=8983\n");
        assertEquals(MemoryEditorService.SolrMode.LINE, svc.read().solrMode());
        svc.writeSolrHeap(2048);
        assertEquals("SOLR_HEAP=\"2048m\"\nSOLR_PORT=8983\n", Files.readString(solrIn));
    }

    @Test
    void solrJavaMemIsNotEditable() throws Exception {
        Files.writeString(solrIn, "SOLR_JAVA_MEM=\"-Xms512m -Xmx512m\"\n");
        assertEquals(MemoryEditorService.SolrMode.READONLY, svc.read().solrMode());
        var p = svc.preview(new MemoryEditorService.Input(null, 2048L, null));
        assertTrue(p.findings().stream().anyMatch(f -> f.message().contains("SOLR_JAVA_MEM")));
    }

    @Test
    void aChoiceThatCannotStartIsRefused() {
        var p = svc.preview(new MemoryEditorService.Input(12000L, null, null));
        assertTrue(p.findings().stream().anyMatch(f -> f.message().contains("refuserait de démarrer")), p.findings().toString());
    }

    @Test
    void heapBoundsAreChecked() {
        assertTrue(svc.preview(new MemoryEditorService.Input(100L, null, null)).findings().stream().anyMatch(f -> f.where().equals("appHeapMb")));
        assertTrue(svc.preview(new MemoryEditorService.Input(14000L, null, null)).findings().stream().anyMatch(f -> f.where().equals("appHeapMb")));
    }

    @Test
    void saveWritesAllThreeFiles() throws Exception {
        var r = svc.save(new MemoryEditorService.Input(1536L, 1536L, 4), Set.of());
        assertTrue(r.saved(), r.findings().toString());
        assertTrue(Files.readString(dir.resolve("jvm.conf")).contains("IC_HEAP_MB=1536"));
        assertTrue(Files.readString(solrIn).contains("1536m"));
        assertTrue(Files.readString(app).contains("reservedRamGb: 4"));
    }
}
