package eu.intracherche.intracherche.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class IndexesConfigLoaderStatusTest {

    @Test
    void knownStatusesParseCaseInsensitively() {
        assertThat(IndexesConfigLoader.parseStatus("active", "core")).isEqualTo(Status.ACTIVE);
        assertThat(IndexesConfigLoader.parseStatus(" Paused ", "core")).isEqualTo(Status.PAUSED);
        assertThat(IndexesConfigLoader.parseStatus("DELETE", "core")).isEqualTo(Status.DELETE);
    }

    // "DELETED" (the value is DELETE) used to fall back to ACTIVE: the index kept being
    // fed and searched. An unknown status now pauses it instead.
    @Test
    void unknownStatusPausesTheIndex() {
        assertThat(IndexesConfigLoader.parseStatus("DELETED", "core")).isEqualTo(Status.PAUSED);
        assertThat(IndexesConfigLoader.parseStatus("", "core")).isEqualTo(Status.PAUSED);
    }

    @Test
    void loadPausesAnIndexWithATypoAndLeavesTheOthersAlone(@TempDir Path dir) throws Exception {
        Path yaml = dir.resolve("indexes.yaml");
        Files.writeString(yaml, """
                indexes:
                  - coreName: Index_Typo
                    visibleToGroups: ALL
                    status: DELETED
                  - coreName: Index_Ok
                    visibleToGroups: ALL
                    status: ACTIVE
                  - coreName: Index_Default
                    visibleToGroups: ALL
                """);

        IndexesConfig config = IndexesConfigLoader.load(yaml.toString());

        assertThat(config.getIndexes()).extracting(IndexConfig::getStatus)
                .containsExactly(Status.PAUSED, Status.ACTIVE, Status.ACTIVE);
    }
}
