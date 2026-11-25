package eu.intracherche.intracherche.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigCheckServiceTest {

    @TempDir Path dir;

    private ConfigCheckService.Report run(String appYaml, String indexesYaml) throws Exception {
        Path app = dir.resolve("application.yaml");
        Path idx = dir.resolve("indexes.yaml");
        Files.writeString(app, appYaml);
        Files.writeString(idx, indexesYaml);
        MockEnvironment env = new MockEnvironment()
                .withProperty("spring.config.additional-location", "file:" + app)
                .withProperty("indexing-config.indexesConfigPath", idx.toString())
                .withProperty("admin.adminGroupSid", "S-1-5-21-1");
        return new ConfigCheckService(env, new IndexesConfig()).check();
    }

    private static boolean has(ConfigCheckService.Report r, String severity, String text) {
        return r.findings().stream().anyMatch(f -> f.severity().equals(severity)
                && (f.where().contains(text) || f.message().contains(text)));
    }

    private static final String GOOD_INDEXES = """
            indexes:
              - coreName: A
                visibleToGroups: ALL
                indexedDirs:
                  - mountType: SMB
                    smbServer: s
                    smbShare: x
                    smbUser: u
                    smbPassword: p
            """;

    @Test
    void cleanFilesProduceNoErrorOrWarning() throws Exception {
        var r = run("indexing-config:\n  cycleIntervalMinutes: 5\n", GOOD_INDEXES);
        assertTrue(r.findings().stream().noneMatch(f -> !f.severity().equals(ConfigCheckService.INFO)), r.findings().toString());
        assertEquals(1, r.indexes().size());
    }

    @Test
    void unknownApplicationKeyIsReportedWithSuggestion() throws Exception {
        var r = run("indexing-config:\n  cycleIntervalMinute: 5\n", GOOD_INDEXES);
        assertTrue(has(r, "warning", "cycleIntervalMinutes"));
    }

    @Test
    void wrongTypeIsAnError() throws Exception {
        var r = run("indexing-config:\n  cycleIntervalMinutes: soon\n", GOOD_INDEXES);
        assertTrue(has(r, "error", "Un nombre est attendu"));
    }

    @Test
    void misindentedFileIsReportedWithLine() throws Exception {
        var r = run("indexing-config:\n  cycleIntervalMinutes: 5\n wrong: 1\n", GOOD_INDEXES);
        assertTrue(has(r, "error", "ligne"));
    }

    @Test
    void duplicateKeyIsAnError() throws Exception {
        var r = run("admin:\n  adminGroupSid: a\nadmin:\n  adminGroupSid: b\n", GOOD_INDEXES);
        assertTrue(has(r, "error", "invalide"));
    }

    @Test
    void misindentedIndexBecomesAnUnknownKey() throws Exception {
        String bad = """
                indexes:
                  - coreName: A
                    visibleToGroups: ALL
                    indexedDirs:
                      - mountType: SMB
                        smbServer: s
                        smbShare: x
                        smbUser: u
                        smbPassword: p
                    coreName2: B
                    ocrr: true
                """;
        var r = run("", bad);
        assertTrue(has(r, "warning", "ocrr"));
        assertTrue(has(r, "warning", "coreName2"));
    }

    @Test
    void placeholderPasswordAndMissingVisibilityAreErrors() throws Exception {
        String bad = """
                indexes:
                  - coreName: A
                    indexedDirs:
                      - mountType: SMB
                        smbServer: s
                        smbShare: x
                        smbUser: u
                        smbPassword: CHANGE_ME
                """;
        var r = run("", bad);
        assertTrue(has(r, "error", "CHANGE_ME"));
        assertTrue(has(r, "error", "visibleToGroups"));
    }

    @Test
    void secretsAreMasked() throws Exception {
        MockEnvironment env = new MockEnvironment().withProperty("jwt.secret", "supersecretvalue");
        var r = new ConfigCheckService(env, new IndexesConfig()).check();
        r.settings().stream().filter(s -> s.key().equals("jwt.secret"))
                .forEach(s -> assertTrue(!s.value().contains("supersecret")));
    }
}
