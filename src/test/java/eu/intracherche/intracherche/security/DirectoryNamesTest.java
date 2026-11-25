package eu.intracherche.intracherche.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DirectoryNamesTest {

    @Test
    @DisplayName("a real account name is its own directory name")
    void plainName() {
        assertThat(DirectoryNames.of("alice")).isEqualTo("alice");
        assertThat(DirectoryNames.of(null)).isNull();
    }

    @Test
    @DisplayName("a visitor session reads the directory account before the bar")
    void visitorName() {
        String session = DirectoryNames.visitor("demo", "a3f9c2d41b7e0856");
        assertThat(session).isEqualTo("demo|a3f9c2d41b7e0856");
        assertThat(DirectoryNames.of(session)).isEqualTo("demo");
    }

    @Test
    @DisplayName("a visitor is shown as \"Visiteur 493C\", a real account under its own name")
    void displayName() {
        assertThat(DirectoryNames.display("demo|493cc4bbddcc1371")).isEqualTo("Visiteur 493C");
        assertThat(DirectoryNames.display("alice")).isEqualTo("alice");
        assertThat(DirectoryNames.display("demo|")).isEqualTo("demo");
        assertThat(DirectoryNames.display(null)).isNull();
    }

    @Test
    @DisplayName("everything after the first bar is dropped, so a crafted name cannot reach another account")
    void firstBarWins() {
        assertThat(DirectoryNames.of("demo|x|alice")).isEqualTo("demo");
        assertThat(DirectoryNames.of("|alice")).isEmpty();
    }
}
