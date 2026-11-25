package eu.intracherche.intracherche.indexing.outage;

import eu.intracherche.intracherche.indexing.outage.ServiceOutageMonitor.Dependency;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ServiceOutageMonitorTest {

    private final AtomicBoolean embeddingUp = new AtomicBoolean(true);
    private ServiceOutageMonitor monitor;

    @BeforeEach
    void setUp() {
        Map<Dependency, BooleanSupplier> checks = new EnumMap<>(Dependency.class);
        checks.put(Dependency.EMBEDDING, embeddingUp::get);
        checks.put(Dependency.OCR, () -> true);
        checks.put(Dependency.NER, () -> true);
        monitor = new ServiceOutageMonitor(checks, Clock.systemUTC(), Duration.ofMillis(50));
    }

    // The service answers /health: the failure was the call's own, nothing to wait for.
    @Test
    void a_failed_call_to_a_service_that_answers_is_not_an_outage() {
        assertThat(monitor.confirmOutage(Dependency.EMBEDDING)).isFalse();
        assertThat(monitor.outages()).isEmpty();
    }

    @Test
    void a_service_that_does_not_answer_is_down_until_it_answers_again() throws Exception {
        embeddingUp.set(false);

        assertThat(monitor.confirmOutage(Dependency.EMBEDDING)).isTrue();
        assertThat(monitor.outages()).containsOnlyKeys(Dependency.EMBEDDING);
        assertThat(monitor.isDown(Dependency.OCR)).isFalse();

        embeddingUp.set(true);
        long start = System.currentTimeMillis();
        monitor.awaitRecovery(Dependency.EMBEDDING, () -> false);

        assertThat(System.currentTimeMillis() - start).isLessThan(5_000);
        assertThat(monitor.outages()).isEmpty();
    }

    // Admin pause, or a worker told to stop: the wait ends, and says the service is still down.
    @Test
    void a_wait_ends_when_asked_to_stop() {
        embeddingUp.set(false);
        monitor.confirmOutage(Dependency.EMBEDDING);

        assertThatThrownBy(() -> monitor.awaitRecovery(Dependency.EMBEDDING, () -> true))
                .isInstanceOf(ServiceUnavailableException.class)
                .extracting(e -> ((ServiceUnavailableException) e).dependency())
                .isEqualTo(Dependency.EMBEDDING);
        assertThat(monitor.awaitAll(() -> true)).isFalse();
    }

    @Test
    void nothing_down_nothing_to_wait_for() throws Exception {
        monitor.awaitRecovery(Dependency.EMBEDDING, () -> true);
        assertThat(monitor.awaitAll(() -> true)).isTrue();
    }
}
