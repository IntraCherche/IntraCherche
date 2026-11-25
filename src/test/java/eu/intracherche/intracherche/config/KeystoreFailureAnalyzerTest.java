package eu.intracherche.intracherche.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.diagnostics.FailureAnalysis;

import java.io.IOException;
import java.nio.file.NoSuchFileException;

import static org.assertj.core.api.Assertions.assertThat;

class KeystoreFailureAnalyzerTest {

    /** The chain seen when a test VM's application.yaml was put on a freshly installed server. */
    @Test
    void a_password_copied_from_another_server_is_explained_with_the_way_out() {
        Throwable failure = new RuntimeException("Unable to start web server",
                new IllegalStateException("Could not load store from '/etc/intracherche/intracherche.p12'",
                        new IOException("keystore password was incorrect",
                                new java.security.UnrecoverableKeyException("failed to decrypt safe contents entry"))));

        FailureAnalysis analysis = new KeystoreFailureAnalyzer().analyze(failure);

        assertThat(analysis).isNotNull();
        assertThat(analysis.getDescription())
                .contains("/etc/intracherche/intracherche.p12")
                .contains("cannot be opened with the password written in application.yaml");
        assertThat(analysis.getAction())
                .contains("another server")
                .contains("generate-certificate.sh")
                .contains("restart");
    }

    @Test
    void a_missing_keystore_file_is_reported_as_missing() {
        Throwable failure = new IllegalStateException("Could not load store from '/etc/intracherche/intracherche.p12'",
                new NoSuchFileException("/etc/intracherche/intracherche.p12"));

        FailureAnalysis analysis = new KeystoreFailureAnalyzer().analyze(failure);

        assertThat(analysis.getDescription()).contains("does not exist");
    }

    /** Anything else keeps Spring's own report. */
    @Test
    void other_failures_are_left_alone() {
        assertThat(new KeystoreFailureAnalyzer().analyze(new IllegalStateException("something else"))).isNull();
        assertThat(new KeystoreFailureAnalyzer().analyze(
                new IllegalStateException("Could not load store from 'x'", new IOException("unrelated")))).isNull();
    }
}
