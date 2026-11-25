package eu.intracherche.intracherche.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** indexes.yaml's visibleToGroups: required, ALL or group names, and who it lets see what. */
class IndexesConfigVisibilityTest {

    @TempDir Path dir;

    private IndexesConfig load(String indexes) throws Exception {
        Path file = dir.resolve("indexes.yaml");
        Files.writeString(file, "indexes:\n" + indexes);
        return IndexesConfigLoader.load(file.toString());
    }

    @Test
    void a_missing_visibleToGroups_stops_the_load_and_says_what_to_write() {
        assertThatThrownBy(() -> load("""
                  - coreName: RH
                    status: ACTIVE
                """))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Index 'RH'")
                .hasMessageContaining("visibleToGroups: ALL");
    }

    @Test
    void an_empty_visibleToGroups_is_refused() {
        assertThatThrownBy(() -> load("  - coreName: RH\n    visibleToGroups: []\n"))
                .hasMessageContaining("empty");
        assertThatThrownBy(() -> load("  - coreName: RH\n    visibleToGroups:\n"))
                .hasMessageContaining("missing");
    }

    @Test
    void ALL_cannot_be_mixed_with_groups() {
        assertThatThrownBy(() -> load("  - coreName: RH\n    visibleToGroups: [ALL, Medical]\n"))
                .hasMessageContaining("mixes ALL");
    }

    @Test
    void ALL_a_single_name_and_a_list_are_read() throws Exception {
        IndexesConfig config = load("""
                  - coreName: Tous
                    visibleToGroups: all
                  - coreName: Un
                    visibleToGroups: Medical
                  - coreName: Plusieurs
                    visibleToGroups: [Medical, " Direction "]
                """);
        List<IndexConfig> indexes = config.getIndexes();
        assertThat(indexes.get(0).isVisibleToAll()).isTrue();
        assertThat(indexes.get(1).isVisibleToAll()).isFalse();
        assertThat(indexes.get(1).getVisibleToGroups()).containsExactly("Medical");
        assertThat(indexes.get(2).getVisibleToGroups()).containsExactly("Medical", "Direction");
    }

    @Test
    void only_members_of_a_listed_group_see_a_restricted_index() throws Exception {
        IndexesConfig config = load("""
                  - coreName: Tous
                    visibleToGroups: ALL
                  - coreName: EMC-Saurat
                    visibleToGroups: [Medical]
                """);
        config.getIndexes().get(1).setVisibleToGroupSids(Map.of("Medical", "S-1-5-21-9-1120"));
        List<String> solrCores = List.of("EMC-Saurat", "Tous", "Orphelin");

        assertThat(config.visibleCores(solrCores, List.of("S-1-5-21-9-1105", "S-1-5-21-9-1120")))
                .containsExactly("EMC-Saurat", "Tous");
        assertThat(config.visibleCores(solrCores, List.of("S-1-5-21-9-1105", "S-1-1-0")))
                .as("not in Medical, and a core indexes.yaml does not declare is seen by nobody")
                .containsExactly("Tous");
    }

    @Test
    void a_restricted_index_whose_groups_were_never_resolved_is_seen_by_nobody() throws Exception {
        IndexesConfig config = load("  - coreName: EMC-Saurat\n    visibleToGroups: [Medical]\n");
        assertThat(config.isVisible("EMC-Saurat", List.of("S-1-5-21-9-1120"))).isFalse();
    }
}
