package eu.intracherche.intracherche.security;

import eu.intracherche.intracherche.config.IndexConfig;
import eu.intracherche.intracherche.config.IndexesConfig;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The startup check of visibleToGroups against AD. */
class IndexVisibilityResolverTest {

    /** A directory where names match without regard to case, as in AD. */
    private static final Map<String, DirectoryGroup> AD = Map.of(
            "medical", new DirectoryGroup("Medical", "S-1-5-21-9-1120", true),
            "direction", new DirectoryGroup("Direction", "S-1-5-21-9-1121", true),
            "docteur-marchal", new DirectoryGroup("docteur-marchal", "S-1-5-21-9-1105", false));

    private final List<String> asked = new ArrayList<>();
    private final Function<String, List<DirectoryGroup>> directory = name -> {
        asked.add(name);
        DirectoryGroup found = AD.get(name.toLowerCase());
        return found == null ? List.of() : List.of(found);
    };

    private static IndexesConfig config(IndexConfig... indexes) {
        IndexesConfig config = new IndexesConfig();
        config.setIndexes(List.of(indexes));
        return config;
    }

    private static IndexConfig restricted(String core, String... groups) {
        IndexConfig index = new IndexConfig();
        index.setCoreName(core);
        index.setVisibleToGroups(List.of(groups));
        return index;
    }

    @Test
    void names_become_SIDs_spelled_as_AD_spells_them_and_each_is_asked_once() {
        IndexConfig saurat = restricted("EMC-Saurat", "MEDICAL", "Direction");
        IndexConfig autre = restricted("Autre", "MEDICAL");
        IndexConfig tous = new IndexConfig();
        tous.setCoreName("Tous");
        tous.setVisibleToAll(true);

        IndexVisibilityResolver.resolve(config(saurat, autre, tous), directory);

        assertThat(saurat.getVisibleToGroupSids())
                .containsExactly(Map.entry("Medical", "S-1-5-21-9-1120"), Map.entry("Direction", "S-1-5-21-9-1121"));
        assertThat(saurat.isVisibleTo(List.of("S-1-5-21-9-1121"))).isTrue();
        assertThat(saurat.isVisibleTo(List.of("S-1-5-21-9-1105"))).isFalse();
        assertThat(asked).containsExactly("MEDICAL", "Direction");
    }

    @Test
    void an_unknown_name_stops_the_startup() {
        assertThatThrownBy(() -> IndexVisibilityResolver.resolve(config(restricted("EMC-Saurat", "Medicale")), directory))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Group name 'Medicale' for setting visibleToGroups of index 'EMC-Saurat' does not exist in AD. "
                        + "Please carefully check this name (the group's pre-Windows 2000 name) and correct it before "
                        + "starting up again.");
    }

    @Test
    void a_user_is_not_a_group() {
        assertThatThrownBy(() -> IndexVisibilityResolver.resolve(config(restricted("EMC-Saurat", "docteur-marchal")), directory))
                .hasMessageContaining("'docteur-marchal' in visibleToGroups of index 'EMC-Saurat' is not a group in AD");
    }

    @Test
    void an_unreadable_directory_stops_the_startup() {
        Function<String, List<DirectoryGroup>> down = name -> { throw new RuntimeException("connection refused"); };
        assertThatThrownBy(() -> IndexVisibilityResolver.resolve(config(restricted("EMC-Saurat", "Medical")), down))
                .hasMessageContaining("Could not read Active Directory")
                .hasMessageContaining("connection refused");
    }

    @Test
    void ALL_needs_no_directory() {
        IndexConfig tous = new IndexConfig();
        tous.setCoreName("Tous");
        tous.setVisibleToAll(true);
        IndexVisibilityResolver.resolve(config(tous), name -> { throw new AssertionError("AD asked"); });
        assertThat(tous.isVisibleTo(List.of())).isTrue();
    }
}
