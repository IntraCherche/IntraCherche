package eu.intracherche.intracherche.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.diagnostics.FailureAnalysis;

import static org.assertj.core.api.Assertions.assertThat;

class IndexesConfigFailureAnalyzerTest {

    @Test
    void a_visibility_mistake_is_reported_as_its_message_and_what_to_correct() {
        IndexesConfigException cause = new IndexesConfigException("Group name 'Finanse' does not exist in AD.");
        // Wrapped, as Spring hands it over: the bean creation failure around it.
        FailureAnalysis analysis = new IndexesConfigFailureAnalyzer()
                .analyze(new IllegalStateException("Error creating bean 'indexesConfig'", cause));

        assertThat(analysis).isNotNull();
        assertThat(analysis.getDescription()).isEqualTo("Group name 'Finanse' does not exist in AD.");
        assertThat(analysis.getAction()).contains("indexes.yaml");
    }
}
