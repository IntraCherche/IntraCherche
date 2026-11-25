package eu.intracherche.intracherche.indexing.outage;

import eu.intracherche.intracherche.services.EmbeddingService;
import eu.intracherche.intracherche.services.NerService;
import eu.intracherche.intracherche.services.OcrService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

/**
 * Holds indexing while a Python service it depends on is down, instead of indexing without it.
 *
 * <p>A file indexed without its vectors, its place names or the text of a scanned page is marked
 * indexed all the same, and never gets them back until it changes. So when a call to a service
 * fails and its {@code /health} does not answer either, the service is recorded as down: callers
 * wait in {@link #awaitRecovery} and retry the call, files not started yet wait before being
 * read, and nothing already done is thrown away. The service is polled every
 * {@link #POLL_INTERVAL}; indexing resumes by itself once it answers. The admin panel shows the
 * outage for as long as it lasts.</p>
 *
 * <p>A failed call while {@code /health} answers is the call's own failure — one page the service
 * cannot handle — and is left to the caller, as before.</p>
 */
@Component
public class ServiceOutageMonitor {

    private static final Logger log = LoggerFactory.getLogger(ServiceOutageMonitor.class);

    static final Duration POLL_INTERVAL = Duration.ofSeconds(10);
    /** How often a waiting caller looks at the outage and at its stop condition. */
    private static final long WAIT_STEP_MS = 500;

    public enum Dependency {
        EMBEDDING("embedding", "Service d'embedding"),
        OCR("ocr", "Service OCR"),
        NER("ner", "Service NER");

        /** As in the logs and the admin panel's service list. */
        public final String id;
        /** Shown to the admin. */
        public final String label;

        Dependency(String id, String label) {
            this.id = id;
            this.label = label;
        }
    }

    private final Map<Dependency, BooleanSupplier> healthChecks;
    private final Clock clock;
    private final Duration pollInterval;
    /** Services down, with the moment the outage was noticed. */
    private final Map<Dependency, Instant> down = new ConcurrentHashMap<>();

    @Autowired
    public ServiceOutageMonitor(EmbeddingService embeddingService, OcrService ocrService, NerService nerService) {
        this(healthChecks(embeddingService, ocrService, nerService), Clock.systemUTC(), POLL_INTERVAL);
    }

    ServiceOutageMonitor(Map<Dependency, BooleanSupplier> healthChecks, Clock clock, Duration pollInterval) {
        this.healthChecks = healthChecks;
        this.clock = clock;
        this.pollInterval = pollInterval;
    }

    private static Map<Dependency, BooleanSupplier> healthChecks(EmbeddingService embeddingService,
                                                                 OcrService ocrService, NerService nerService) {
        Map<Dependency, BooleanSupplier> checks = new EnumMap<>(Dependency.class);
        checks.put(Dependency.EMBEDDING, embeddingService::isHealthy);
        // Pages go to the GPU node first, then to this server's service: either one reads them.
        checks.put(Dependency.OCR, () -> ocrService.isHealthy() || ocrService.nodeHealth() != null);
        checks.put(Dependency.NER, nerService::isHealthy);
        return checks;
    }

    /**
     * Called after a call to the service failed. Returns true when the service is down — its
     * {@code /health} does not answer either — and the caller should {@link #awaitRecovery} and
     * retry; false when the failure is the call's own, for the caller to handle as before.
     */
    public boolean confirmOutage(Dependency dependency) {
        if (down.containsKey(dependency)) return true;
        if (healthChecks.get(dependency).getAsBoolean()) return false;
        if (down.putIfAbsent(dependency, clock.instant()) == null) {
            log.warn("The {} service is unreachable: indexing waits for it (no file is indexed without it) and checks it again every {} s",
                    dependency.id, pollInterval.toSeconds());
            Thread.ofVirtual().name("outage-" + dependency.id).start(() -> watch(dependency));
        }
        return true;
    }

    private void watch(Dependency dependency) {
        try {
            do {
                Thread.sleep(pollInterval);
            } while (!healthChecks.get(dependency).getAsBoolean());
        } catch (InterruptedException e) {
            return;
        }
        Instant since = down.remove(dependency);
        log.info("The {} service is back after {}: indexing resumes",
                dependency.id, format(Duration.between(since, clock.instant())));
    }

    /**
     * Blocks while the service is down.
     *
     * @param stop checked while waiting: an admin pause, or a worker told to stop
     * @throws ServiceUnavailableException when {@code stop} became true or the thread was
     *         interrupted before the service came back
     */
    public void awaitRecovery(Dependency dependency, BooleanSupplier stop) throws ServiceUnavailableException {
        while (down.containsKey(dependency)) {
            if (stop.getAsBoolean()) throw new ServiceUnavailableException(dependency);
            try {
                Thread.sleep(WAIT_STEP_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ServiceUnavailableException(dependency);
            }
        }
    }

    /**
     * Blocks while any service is down: a file is not read before indexing can finish it.
     *
     * @return false when {@code stop} became true or the thread was interrupted first
     */
    public boolean awaitAll(BooleanSupplier stop) {
        while (!down.isEmpty()) {
            if (stop.getAsBoolean()) return false;
            try {
                Thread.sleep(WAIT_STEP_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    public boolean isDown(Dependency dependency) {
        return down.containsKey(dependency);
    }

    /** The services down, with when each outage was noticed. */
    public Map<Dependency, Instant> outages() {
        Map<Dependency, Instant> copy = new EnumMap<>(Dependency.class);
        copy.putAll(down);
        return copy;
    }

    private static String format(Duration d) {
        long s = d.toSeconds();
        return s < 60 ? s + " s" : (s / 60) + " min " + (s % 60) + " s";
    }
}
