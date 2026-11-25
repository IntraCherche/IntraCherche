package eu.intracherche.intracherche.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LlmNumThreadResolutionTest {

    @Test
    void a_written_value_is_used_as_it_is() {
        assertThat(LlmConfig.resolveNumThread(3, 16)).isEqualTo(3);
        assertThat(LlmConfig.resolveNumThread(12, 4)).isEqualTo(12);
    }

    @Test
    void automatic_leaves_two_cores_from_six_cores_up() {
        assertThat(LlmConfig.resolveNumThread(0, 8)).isEqualTo(6);   // the measured case: 6 of 8 vCPUs
        assertThat(LlmConfig.resolveNumThread(0, 6)).isEqualTo(4);
        assertThat(LlmConfig.resolveNumThread(0, 32)).isEqualTo(30);
    }

    @Test
    void automatic_leaves_small_machines_to_ollama() {
        assertThat(LlmConfig.resolveNumThread(0, 4)).isZero();
        assertThat(LlmConfig.resolveNumThread(0, 2)).isZero();
        assertThat(LlmConfig.resolveNumThread(0, 1)).isZero();
    }

    @Test
    void a_negative_value_counts_as_automatic() {
        LlmConfig config = new LlmConfig();
        config.setNumThread(-5);
        assertThat(config.getNumThread()).isZero();
    }
}
