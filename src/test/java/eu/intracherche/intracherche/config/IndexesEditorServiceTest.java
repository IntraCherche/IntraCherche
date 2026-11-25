package eu.intracherche.intracherche.config;

import eu.intracherche.intracherche.config.IndexesEditorService.DirDto;
import eu.intracherche.intracherche.config.IndexesEditorService.IndexDto;
import eu.intracherche.intracherche.security.DirectoryGroup;
import eu.intracherche.intracherche.security.SecurityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class IndexesEditorServiceTest {

    @TempDir Path dir;
    Path file;
    IndexesEditorService svc;
    SecurityService security = Mockito.mock(SecurityService.class);

    @BeforeEach
    void setUp() throws Exception {
        file = dir.resolve("indexes.yaml");
        Files.writeString(file, """
                indexes:
                  - coreName: Index_1
                    visibleToGroups: ALL
                    ocr: true
                    junk: 1
                    indexedDirs:
                      - mountType: SMB
                        smbServer: srv
                        smbShare: HR
                        smbUser: svc
                        smbPassword: "s3cr#t: x"
                """);
        MockEnvironment env = new MockEnvironment().withProperty("indexing-config.indexesConfigPath", file.toString());
        svc = new IndexesEditorService(env, new ConfigCheckService(env, new IndexesConfig()), security);
        Mockito.when(security.findBySamAccountName("Medical"))
                .thenReturn(List.of(new DirectoryGroup("Medical", "S-1-5-21-9", true)));
        Mockito.when(security.findBySamAccountName("Nope")).thenReturn(List.of());
    }

    private static IndexDto index(String name, String status, boolean all, List<String> groups, DirDto... dirs) {
        return new IndexDto(name, status, false, true, null, all, groups, List.of(dirs));
    }

    private static DirDto dir(String pwd) { return new DirDto("srv", "HR", "svc", null, pwd, false); }

    @Test
    void readNeverReturnsPasswordsAndListsIgnoredKeys() {
        var c = svc.read();
        assertEquals(1, c.indexes().size());
        assertNull(c.indexes().get(0).indexedDirs().get(0).smbPassword());
        assertTrue(c.indexes().get(0).indexedDirs().get(0).passwordSet());
        assertTrue(c.ignoredKeys().contains("Index_1 › junk"));
    }

    @Test
    void saveKeepsStoredPasswordWhenBlankAndWritesHeaderAndBackup() throws Exception {
        var res = svc.save(List.of(
                index("Index_1", "PAUSED", true, List.of(), dir("")),
                index("New_2", "ACTIVE", false, List.of("Medical"), dir("pw2"))));
        assertTrue(res.saved(), res.findings().toString());
        assertNotNull(res.backup());
        String text = Files.readString(file);
        assertTrue(text.startsWith("# Auto generated"));
        IndexesConfig loaded = IndexesConfigLoader.load(file.toString());
        assertEquals(2, loaded.getIndexes().size());
        assertEquals("s3cr#t: x", loaded.getIndexes().get(0).getIndexedDirs().get(0).getSmbPassword());
        assertEquals(Status.PAUSED, loaded.getIndexes().get(0).getStatus());
        assertEquals(List.of("Medical"), loaded.getIndexes().get(1).getVisibleToGroups());
        assertFalse(text.contains("junk"));
    }

    @Test
    void removingAnExistingIndexIsRefused() throws Exception {
        var res = svc.save(List.of(index("Other", "ACTIVE", true, List.of(), dir("p"))));
        assertFalse(res.saved());
        assertTrue(res.findings().stream().anyMatch(f -> f.message().contains("ne peut pas être retiré")));
    }

    @Test
    void unknownGroupAndMissingPasswordAreRefusedAndFileUntouched() throws Exception {
        String before = Files.readString(file);
        var res = svc.save(List.of(
                index("Index_1", "ACTIVE", true, List.of(), dir("")),
                index("New_2", "ACTIVE", false, List.of("Nope"), new DirDto("other", "X", "u", null, "", false))));
        assertFalse(res.saved());
        assertTrue(res.findings().stream().anyMatch(f -> f.message().contains("n'existe pas dans l'annuaire")));
        assertTrue(res.findings().stream().anyMatch(f -> f.where().contains("smbPassword")));
        assertEquals(before, Files.readString(file));
    }

    @Test
    void invalidNewCoreNameIsRefused() {
        var f = svc.validate(List.of(
                index("Index_1", "ACTIVE", true, List.of(), dir("")),
                index("mon index é", "ACTIVE", true, List.of(), dir("p"))));
        assertTrue(f.stream().anyMatch(x -> x.message().contains("Nom d'index invalide")));
    }
}
