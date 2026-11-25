package eu.intracherche.intracherche.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every indexing-config key documented in application.yaml must reach IndexingConfig through
 * Spring's binding — the path the application uses. cycleIntervalMinutes was silently ignored
 * until 2026-09-29: the record's component had another name.
 */
class IndexingConfigBindingTest {

    private static IndexingConfig bind(Map<String, String> keys) {
        return new Binder(new MapConfigurationPropertySource(keys))
                .bind("indexing-config", IndexingConfig.class).get();
    }

    @Test
    void every_documented_key_is_taken_into_account() {
        IndexingConfig cfg = bind(Map.of(
                "indexing-config.indexesConfigPath", "config/indexes.yaml",
                "indexing-config.cycleIntervalMinutes", "7",
                "indexing-config.maxFileSizeMb", "100",
                "indexing-config.maxTextChars", "1000"));

        assertThat(cfg.cycleIntervalMs()).isEqualTo(7 * 60_000L);
        assertThat(cfg.maxFileSizeBytes()).isEqualTo(100L * 1024 * 1024);
        assertThat(cfg.maxTextChars()).isEqualTo(1000);
    }

    @Test
    void a_leftover_commitWithinSeconds_is_ignored_not_fatal() {
        IndexingConfig cfg = bind(Map.of(
                "indexing-config.indexesConfigPath", "config/indexes.yaml",
                "indexing-config.commitWithinSeconds", "5"));

        assertThat(cfg.commitWithinMs()).isEqualTo(30_000);
    }

    @Test
    void omitted_keys_take_their_defaults() {
        IndexingConfig cfg = bind(Map.of("indexing-config.indexesConfigPath", "config/indexes.yaml"));

        assertThat(cfg.cycleIntervalMs()).isEqualTo(60_000L);
        assertThat(cfg.commitWithinMs()).isEqualTo(30_000);
        assertThat(cfg.maxFileSizeMb()).isEqualTo(IndexingConfig.DEFAULT_MAX_FILE_SIZE_MB);
        assertThat(cfg.maxTextChars()).isEqualTo(IndexingConfig.DEFAULT_MAX_TEXT_CHARS);
    }
}
