package eu.intracherche.intracherche.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The per-index fields of indexes.yaml, as the "Champs disponibles" table in INSTALL.md
 * describes them.
 */
class IndexesConfigSettingsTest {

    @TempDir
    Path dir;

    @Test
    void ocr_defaults_to_true_and_can_be_turned_off() throws Exception {
        IndexesConfig config = load("""
                indexes:
                  - coreName: RH
                    visibleToGroups: ALL
                  - coreName: Archives
                    visibleToGroups: ALL
                    ocr: false
                """);

        assertThat(index(config, "RH").isOcr()).isTrue();
        assertThat(index(config, "Archives").isOcr()).isFalse();
    }

    @Test
    void allowed_extensions_default_to_the_documented_set() throws Exception {
        IndexesConfig config = load("""
                indexes:
                  - coreName: RH
                    visibleToGroups: ALL
                """);

        assertThat(index(config, "RH").resolvedAllowedExtensions()).containsExactlyInAnyOrder(
                "pdf", "doc", "docx", "odt", "xls", "xlsx", "ods", "ppt", "pptx", "odp", "txt", "eml", "msg");
    }

    /** "Insensible à la casse": the scanners lower-case the file's extension, the loader the list. */
    @Test
    void allowed_extensions_restrict_the_set_case_insensitively() throws Exception {
        IndexesConfig config = load("""
                indexes:
                  - coreName: Archives
                    visibleToGroups: ALL
                    allowedExtensions: [PDF, " Docx ", txt]
                """);

        assertThat(index(config, "Archives").resolvedAllowedExtensions())
                .containsExactlyInAnyOrder("pdf", "docx", "txt");
    }

    @Test
    void status_defaults_to_active() throws Exception {
        assertThat(index(load("""
                indexes:
                  - coreName: RH
                    visibleToGroups: ALL
                """), "RH").getStatus()).isEqualTo(Status.ACTIVE);
    }

    @Test
    void delete_with_its_confirmation_deletes() throws Exception {
        IndexConfig index = index(load("""
                indexes:
                  - coreName: Old
                    visibleToGroups: ALL
                    status: DELETE
                    confirmDelete: true
                """), "Old");

        assertThat(index.getStatus()).isEqualTo(Status.DELETE);
        assertThat(index.isConfirmDelete()).isTrue();
    }

    /**
     * confirmDelete is a safeguard: without it nothing is deleted. It also used to leave the
     * index ACTIVE — still fed from its share, although the admin had asked for it to go.
     */
    @Test
    void delete_without_its_confirmation_pauses_the_index() throws Exception {
        IndexConfig index = index(load("""
                indexes:
                  - coreName: Old
                    visibleToGroups: ALL
                    status: DELETE
                """), "Old");

        assertThat(index.getStatus()).isEqualTo(Status.PAUSED);
        assertThat(index.isConfirmDelete()).isFalse();
    }

    private IndexesConfig load(String yaml) throws Exception {
        Path file = dir.resolve("indexes.yaml");
        Files.writeString(file, yaml);
        return IndexesConfigLoader.load(file.toString());
    }

    private static IndexConfig index(IndexesConfig config, String coreName) {
        return config.getIndexes().stream()
                .filter(i -> i.getCoreName().equals(coreName)).findFirst().orElseThrow();
    }
}
