package eu.intracherche.intracherche.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * reranker.candidates against the device defaults, and llm.enabled reaching the reranker, through
 * the real Spring binding.
 */
class RerankerSettingsTest {

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(RerankerConfig.class, LlmConfig.class);

    @Test
    void auto_follows_the_device() {
        context.run(ctx -> {
            RerankerConfig reranker = ctx.getBean(RerankerConfig.class);
            assertThat(reranker.resolvedCandidates(false)).isEqualTo(40);
            assertThat(reranker.resolvedCandidates(true)).isEqualTo(100);
            assertThat(reranker.maxPassagesHere())
                    .as("started before its device is known: never below what a GPU is sent").isEqualTo(100);
        });
    }

    @Test
    void an_explicit_value_wins_over_either_device() {
        context.withPropertyValues("reranker.candidates=60")
                .run(ctx -> {
                    RerankerConfig reranker = ctx.getBean(RerankerConfig.class);
                    assertThat(reranker.resolvedCandidates(false)).isEqualTo(60);
                    assertThat(reranker.resolvedCandidates(true)).isEqualTo(60);
                    assertThat(reranker.maxPassagesHere()).isEqualTo(60);
                });
    }

    @Test
    void a_reranker_on_another_machine_is_not_started_here() {
        context.withPropertyValues("reranker.pythonUrl=http://192.0.2.34:8003")
                .run(ctx -> assertThat(ctx.getBean(RerankerConfig.class).startsHere()).isFalse());
    }

    @Test
    void llm_enabled_false_in_the_yaml_turns_the_reranker_off() {
        context.withPropertyValues("llm.enabled=false")
                .run(ctx -> {
                    RerankerConfig reranker = ctx.getBean(RerankerConfig.class);
                    assertThat(reranker.isUsed()).isFalse();
                    assertThat(reranker.startsHere()).isFalse();
                });
    }

    @Test
    void by_default_the_reranker_is_used_and_started_here() {
        context.run(ctx -> {
            RerankerConfig reranker = ctx.getBean(RerankerConfig.class);
            assertThat(reranker.isUsed()).isTrue();
            assertThat(reranker.startsHere()).isTrue();
        });
    }
}
