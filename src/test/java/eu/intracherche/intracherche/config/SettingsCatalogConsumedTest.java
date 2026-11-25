package eu.intracherche.intracherche.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.intracherche.intracherche.config.SettingsEditorService.Entry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every setting offered in the admin panel must be read by the application. A key that is misspelled in the
 * catalog, or that no code reads any more, would be saved and silently do nothing.
 *
 * A key counts as consumed when the value we would write changes what a {@code @ConfigurationProperties}
 * class binds to, or when the sources read it by name ({@code @Value}, {@code getProperty}, logback).
 */
class SettingsCatalogConsumedTest {

    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    @Test
    void everyCatalogKeyIsReadByTheApplication() throws Exception {
        List<Entry> catalog = new SettingsEditorService(new MockEnvironment(),
                new ConfigCheckService(new MockEnvironment(), new IndexesConfig()), null, null).catalog();
        Map<String, Class<?>> propsClasses = configurationPropertiesClasses();
        String sources = readSources();

        List<String> unread = new ArrayList<>();
        for (Entry e : catalog) {
            if (readByName(sources, e.key())) continue;
            if (boundByClass(propsClasses, e)) continue;
            unread.add(e.key());
        }
        assertTrue(unread.isEmpty(), "Catalog keys that no code reads (typo, or the setting was removed): " + unread);
    }

    @Test
    void everyCatalogKeyIsInTheBundledDefaultsOrHasADeliberateReason() throws Exception {
        // A key absent from application.yaml has no default to show in the panel and no place in the file.
        Map<String, Object> defaults = ConfigCheckService.flatten(new ConfigCheckService(new MockEnvironment(), new IndexesConfig())
                .loadBundledDefaults());
        List<String> missing = new ArrayList<>();
        for (Entry e : new SettingsEditorService(new MockEnvironment(),
                new ConfigCheckService(new MockEnvironment(), new IndexesConfig()), null, null).catalog()) {
            if ("secret".equals(e.type())) continue;
            if ("spring.mail.host".equals(e.key())) continue;   // no host on purpose: no host, no email
            if (!defaults.containsKey(e.key())) missing.add(e.key());
        }
        assertTrue(missing.isEmpty(), "Catalog keys with no default in the bundled application.yaml: " + missing);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static boolean readByName(String sources, String key) {
        String relaxed = key.replace("-", "");
        return sources.contains("${" + key + "}") || sources.contains("${" + key + ":")
                || sources.contains("getProperty(\"" + key + "\"")
                || sources.contains("source=\"" + key + "\"")
                || sources.contains("${" + relaxed + "}") || sources.contains("${" + relaxed + ":");
    }

    private static boolean boundByClass(Map<String, Class<?>> classes, Entry e) throws Exception {
        String best = null;
        for (String prefix : classes.keySet()) {
            if (e.key().startsWith(prefix.isEmpty() ? "" : prefix + ".") && (best == null || prefix.length() > best.length())) best = prefix;
        }
        if (best == null) return false;
        Class<?> cls = classes.get(best);
        for (String candidate : candidates(e)) {
            Map<String, String> base = bundledDefaults();
            Map<String, String> changed = new HashMap<>(base);
            changed.put(e.key(), candidate);
            JsonNode with = bind(cls, best, changed);
            JsonNode without = bind(cls, best, base);
            if (with != null && !with.equals(without)) return true;
        }
        return false;
    }

    /** The shipped defaults: some classes refuse to bind without their other required values. */
    private static Map<String, String> bundledDefaults() {
        Map<String, String> out = new HashMap<>();
        ConfigCheckService.flatten(new ConfigCheckService(new MockEnvironment(), new IndexesConfig()).loadBundledDefaults())
                .forEach((k, v) -> {
                    if (v != null && !String.valueOf(v).startsWith("${") && !String.valueOf(v).startsWith("@")) out.put(k, String.valueOf(v));
                });
        return out;
    }

    private static List<String> candidates(Entry e) {
        return switch (e.type()) {
            case "bool" -> List.of("true", "false");
            case "int" -> List.of(String.valueOf(e.max() != null ? e.max() : 7777),
                    String.valueOf(e.min() != null ? e.min() : 1));
            default -> List.of("zz-test-value", "https://zz.example.org");
        };
    }

    private static JsonNode bind(Class<?> cls, String prefix, Map<String, String> props) {
        try {
            Object bound = new Binder(new MapConfigurationPropertySource(props))
                    .bind(prefix, Bindable.of(cls)).orElse(null);
            return bound == null ? JSON.nullNode() : JSON.valueToTree(bound);
        } catch (Exception ex) {
            return null; // a value this class cannot take (e.g. validation) is not evidence of consumption
        }
    }

    private static Map<String, Class<?>> configurationPropertiesClasses() throws ClassNotFoundException {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(ConfigurationProperties.class));
        Map<String, Class<?>> out = new HashMap<>();
        for (var bd : scanner.findCandidateComponents("eu.intracherche")) {
            Class<?> c = Class.forName(bd.getBeanClassName());
            out.put(c.getAnnotation(ConfigurationProperties.class).prefix(), c);
        }
        // Settings Spring Boot itself reads: bound the same way it does.
        out.put("spring.mail", org.springframework.boot.mail.autoconfigure.MailProperties.class);
        return out;
    }

    private static String readSources() throws IOException {
        StringBuilder sb = new StringBuilder();
        for (String root : List.of("src/main/java", "src/main/resources")) {
            try (Stream<Path> files = Files.walk(Path.of(root))) {
                for (Path p : (Iterable<Path>) files.filter(Files::isRegularFile)
                        .filter(f -> f.toString().endsWith(".java") || f.toString().endsWith(".xml"))::iterator) {
                    sb.append(Files.readString(p)).append('\n');
                }
            }
        }
        return sb.toString();
    }
}
