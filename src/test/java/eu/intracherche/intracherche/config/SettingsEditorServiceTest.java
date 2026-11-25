package eu.intracherche.intracherche.config;

import eu.intracherche.intracherche.config.ConfigCheckService.Finding;
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
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SettingsEditorServiceTest {

    @TempDir Path dir;
    Path file;
    SettingsEditorService svc;
    LdapConnectionTester ldap;
    static final String SID = "S-1-5-21-1-2-3-1112";

    @BeforeEach
    void setUp() throws Exception {
        file = dir.resolve("application.yaml");
        Files.writeString(file, """
                # a comment that will go away
                jwt:
                  secret: ${JWT_SECRET}
                admin:
                  adminGroupSid: "S-1-5-21-1-2-3-1112"
                indexing-config:
                  indexesConfigPath: /etc/x/indexes.yaml
                  cycleIntervalMinutes: 5
                spring:
                  ldap:
                    urls: ldaps://dc:686
                    password: "p@ss: word"
                """);
        MockEnvironment env = new MockEnvironment().withProperty("spring.config.additional-location", "file:" + file);
        SecurityService sec = Mockito.mock(SecurityService.class);
        ldap = Mockito.mock(LdapConnectionTester.class);
        Mockito.when(ldap.test(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any())).thenReturn(java.util.Optional.empty());
        Mockito.when(sec.findBySamAccountName("Admins")).thenReturn(List.of(new DirectoryGroup("Admins", SID, true)));
        svc = new SettingsEditorService(env, new ConfigCheckService(env, new IndexesConfig()), sec, ldap);
    }

    @Test
    void everyCatalogKeyExistsInTheBundledDefaults() {
        var defaults = ConfigCheckService.flatten(new ConfigCheckService(new MockEnvironment(), new IndexesConfig()).loadBundledDefaults());
        for (var e : svc.catalog()) assertTrue(defaults.containsKey(e.key()) || e.key().equals("spring.mail.host"), e.key());   // no mail host on purpose
    }

    @Test
    void readShowsFileValueDefaultAndModifiedFlag() {
        var v = svc.read();
        var cycle = v.settings().stream().filter(s -> s.key().equals("indexing-config.cycleIntervalMinutes")).findFirst().orElseThrow();
        assertEquals(5, cycle.value());
        assertEquals(1, cycle.defaultValue());
        assertTrue(cycle.modified());
        var fil = v.settings().stream().filter(s -> s.key().equals("indexing-config.maxFileSizeMb")).findFirst().orElseThrow();
        assertFalse(fil.modified());
    }

    @Test
    void saveChangesOnlyAskedKeysKeepsUnknownSectionsAndResetsToDefault() throws Exception {
        var res = svc.save(Map.of("indexing-config.cycleIntervalMinutes", 20, "search.rateLimitPerMinute", "30"), Set.of(SID));
        assertTrue(res.saved(), res.findings().toString());
        String text = Files.readString(file);
        assertTrue(text.startsWith("# Auto generated"));
        assertFalse(text.contains("a comment that will go away"));
        assertTrue(text.contains("p@ss: word"));
        assertTrue(text.contains("${JWT_SECRET}"));
        // reset to default removes the key and its empty section
        var res2 = svc.save(java.util.Collections.singletonMap("search.rateLimitPerMinute", null), Set.of(SID));
        assertTrue(res2.saved());
        assertFalse(Files.readString(file).contains("rateLimitPerMinute"));
        assertFalse(Files.readString(file).contains("search:"));
    }

    @Test
    void invalidValuesAreRefusedAndFileUntouched() throws Exception {
        String before = Files.readString(file);
        var res = svc.save(Map.of("indexing-config.cycleIntervalMinutes", 0,
                "alerts.email.from", "not-an-address", "ocr.pythonUrl", "ftp://x",
                "admin.adminGroupSid", ""), Set.of(SID));
        assertFalse(res.saved());
        assertEquals(4, res.findings().stream().filter(f -> f.severity().equals("error")).count(), res.findings().toString());
        assertEquals(before, Files.readString(file));
    }

    @Test
    void changingAdminGroupToOneYouAreNotInIsRefused() throws Exception {
        List<Finding> f = svc.validate(Map.of("admin.adminGroupSid", "S-1-5-21-9-9-9-500"), Set.of(SID));
        assertTrue(f.stream().anyMatch(x -> x.message().contains("perdriez l'accès")));
        assertTrue(svc.validate(Map.of("admin.adminGroupSid", "S-1-5-21-9-9-9-500"), Set.of("S-1-5-21-9-9-9-500")).isEmpty());
    }

    @Test
    void rerankerCandidatesMustExceedSnippets() {
        var f = svc.validate(Map.of("reranker.candidates", 3), Set.of(SID));
        assertTrue(f.stream().anyMatch(x -> x.where().equals("reranker.candidates")));
    }

    @Test
    void ldapSettingsAreTriedBeforeSavingAndABadOneIsRefused() throws Exception {
        Mockito.when(ldap.test(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(java.util.Optional.of("L'annuaire refuse ce compte ou ce mot de passe."));
        String before = Files.readString(file);
        var res = svc.save(Map.of("spring.ldap.password", "wrong"), Set.of(SID));
        assertFalse(res.saved());
        assertTrue(res.findings().stream().anyMatch(f -> f.message().contains("plus personne ne pourrait se connecter")));
        assertEquals(before, Files.readString(file));
    }

    @Test
    void aBlankPasswordKeepsTheStoredOneAndNothingSecretIsReturned() throws Exception {
        var res = svc.save(Map.of("spring.ldap.password", "", "spring.ldap.base", "dc=x,dc=y"), Set.of(SID));
        assertTrue(res.saved(), res.findings().toString());
        assertTrue(Files.readString(file).contains("p@ss: word"));
        var pw = svc.read().settings().stream().filter(s -> s.key().equals("spring.ldap.password")).findFirst().orElseThrow();
        assertNull(pw.value());
        assertTrue(pw.secretSet());
        assertNull(pw.running());
    }

    @Test
    void ldapFilterMustContainThePlaceholderAndRequiredFieldsCannotBeBlank() {
        var f = svc.validate(Map.of("spring.security.ldap.user.search-filter", "(sAMAccountName=x)", "spring.ldap.base", ""), Set.of(SID));
        assertEquals(2, f.stream().filter(x -> x.severity().equals("error")).count(), f.toString());
    }

    @Test
    void groupNameResolvesToSid() {
        assertEquals(SID, svc.resolveGroup("Admins").orElseThrow().sid());
        assertTrue(svc.resolveGroup("Nope").isEmpty());
    }

    @Test
    void directorySettingsAreWrittenFirstAsFlatKeysAndDottedKeysInTheOldFileAreMerged() throws Exception {
        Files.writeString(file, """
                jwt:
                  secret: x
                spring.ldap.urls: "ldaps://old.example.org"
                spring:
                  ldap:
                    base: "dc=a,dc=b"
                admin:
                  adminGroupSid: "S-1-5-21-1-2-3-1112"
                indexing-config:
                  indexesConfigPath: /etc/x/indexes.yaml
                """);
        var r = svc.save(Map.of("spring.ldap.urls", "ldaps://new.example.org", "indexing-config.cycleIntervalMinutes", 7),
                Set.of(SID));
        assertTrue(r.saved(), r.findings().toString());
        String t = Files.readString(file);
        int first = t.indexOf("Set these up first");
        assertTrue(first > 0, t);
        assertTrue(t.indexOf("spring.ldap.urls: ldaps://new.example.org") > first, t);
        assertTrue(t.indexOf("spring.ldap.base") > first, t);
        assertTrue(t.indexOf("admin.adminGroupSid") > first, t);
        assertTrue(t.indexOf("Everything else") > t.indexOf("admin.adminGroupSid"), t);
        assertEquals(1, t.split("spring\\.ldap\\.urls").length - 1, t);
        assertFalse(t.contains("old.example.org"), t);
        // the key must read the same under Spring's own loader
        var ps = new org.springframework.boot.env.YamlPropertySourceLoader()
                .load("t", new org.springframework.core.io.FileSystemResource(file)).get(0);
        assertEquals("ldaps://new.example.org", ps.getProperty("spring.ldap.urls"));
        assertEquals(7, ps.getProperty("indexing-config.cycleIntervalMinutes"));
    }

    @Test
    void mailTestUsesTheTypedValuesAndKeepsTheStoredPassword() throws Exception {
        Files.writeString(file, "jwt:\n  secret: x\nspring.mail.password: stored-secret\n");
        var mail = Mockito.mock(MailConnectionTester.class);
        Mockito.when(mail.send(Mockito.any(), Mockito.anyInt(), Mockito.any(), Mockito.any(), Mockito.anyBoolean(),
                Mockito.anyBoolean(), Mockito.any(), Mockito.any())).thenReturn(java.util.Optional.empty());
        var env = new org.springframework.mock.env.MockEnvironment().withProperty("spring.config.additional-location", "file:" + file);
        var s = new SettingsEditorService(env, new ConfigCheckService(env, new IndexesConfig()), null, ldap, mail);
        assertTrue(s.testMail(Map.of("spring.mail.host", "smtp.example.org", "spring.mail.port", 25), "me@example.org").isEmpty());
        Mockito.verify(mail).send("smtp.example.org", 25, "", "stored-secret", true, true, "intracherche-noreply@corp.local", "me@example.org");
        assertTrue(s.testMail(Map.of(), "me@example.org").get().contains("serveur de courrier"));
        assertTrue(s.testMail(Map.of("spring.mail.host", "h"), "not-an-address").isPresent());
    }

    @Test
    void anEmptyMailHostRemovesTheKeySoNoEmailIsSent() throws Exception {
        Files.writeString(file, "jwt:\n  secret: x\nspring:\n  mail:\n    host: smtp.example.org\n");
        var r = svc.save(Map.of("spring.mail.host", ""), Set.of(SID));
        assertTrue(r.saved(), r.findings().toString());
        assertFalse(Files.readString(file).contains("host"));
    }
}
