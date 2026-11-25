package eu.intracherche.intracherche.config;

import eu.intracherche.intracherche.config.SettingsEditorService.Entry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * For every setting in the panel: save a non-default value through the editor, then read the written file the
 * way Spring does and check the value is there under the very same key.
 */
class SettingsRoundTripTest {

    @TempDir Path dir;

    @Test
    void everySettingSurvivesASaveAndIsReadBackUnderTheSameKey() throws Exception {
        List<String> failures = new ArrayList<>();
        int n = 0;
        MockEnvironment probe = new MockEnvironment();
        ConfigCheckService probeChecker = new ConfigCheckService(probe, new IndexesConfig());
        Map<String, Object> defaults = ConfigCheckService.flatten(probeChecker.loadBundledDefaults());
        List<Entry> catalog = new SettingsEditorService(probe, probeChecker, null, null).catalog();

        // Guarded on purpose, and covered in SettingsEditorServiceTest: this one needs the asker's own groups.
        Set<String> guarded = Set.of("admin.adminGroupSid");
        Map<String, Object> special = Map.of(
                "spring.security.ldap.user.search-filter", "(sAMAccountName={0})",
                "reranker.candidates", 40,
                "logging.logback.rollingpolicy.max-file-size", "20MB");
        for (Entry e : catalog) {
            if (guarded.contains(e.key())) continue;
            Path file = Files.createDirectories(dir.resolve("c" + (n++))).resolve("application.yaml");
            Files.writeString(file, "jwt:\n  secret: x\n");
            MockEnvironment env = new MockEnvironment().withProperty("spring.config.additional-location", "file:" + file);
            ConfigCheckService checker = new ConfigCheckService(env, new IndexesConfig());
            var ldap = Mockito.mock(LdapConnectionTester.class);
            Mockito.when(ldap.test(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()))
                    .thenReturn(Optional.empty());
            SettingsEditorService svc = new SettingsEditorService(env, checker, null, ldap);

            Object value = special.containsKey(e.key()) ? special.get(e.key()) : valueFor(e, defaults.get(e.key()));
            var res = svc.save(Map.of(e.key(), value), Set.of());
            if (!res.saved()) { failures.add(e.key() + " refused: " + res.findings()); continue; }

            PropertySource<?> ps = new YamlPropertySourceLoader().load("t", new FileSystemResource(file)).get(0);
            Object back = ps.getProperty(e.key());
            if (back == null || !String.valueOf(back).equals(String.valueOf(value)))
                failures.add(e.key() + ": saved " + value + " read back " + back);
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }

    private static Object valueFor(Entry e, Object dflt) {
        return switch (e.type()) {
            case "bool" -> !"true".equals(String.valueOf(dflt));
            case "int" -> {
                long v = dflt == null ? (e.min() != null ? e.min() : 5) : Long.parseLong(String.valueOf(dflt)) + 1;
                if (e.max() != null && v > e.max()) v = e.max() - 1;
                if (e.min() != null && v < e.min()) v = e.min();
                yield (int) v;
            }
            case "url", "ldapUrl" -> e.type().equals("ldapUrl") ? "ldaps://dc.example.org" : "http://zz.example.org:1234";
            case "email" -> "zz@example.org";
            case "groupSid" -> "S-1-5-21-1-2-3-4999";
            default -> "zz-value";
        };
    }
}
