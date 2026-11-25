package eu.intracherche.intracherche.registry;

import eu.intracherche.intracherche.common.events.FileDiscoveredEvent;
import eu.intracherche.intracherche.queues.DiscoveryQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Set;

/**
 * This class does exactly one thing:
 *
 * Consume discovered files from the queue and write them to the registry.
 */
public class RegistryIngestWorker implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(RegistryIngestWorker.class);

    private final DiscoveryQueue queue;
    private final FileRegistry registry;
    private final long scanId;
    private volatile boolean running = true;

    public RegistryIngestWorker(
            DiscoveryQueue queue,
            FileRegistry registry,
            long scanId
    ) {
        this.queue = queue;
        this.registry = registry;
        this.scanId = scanId;
    }

    @Override
    public void run() {
        try {
            while (!Thread.currentThread().isInterrupted()) {

                FileDiscoveredEvent event = queue.take();

                // Check for shutdown signal
                if (event.path().equals(Path.of("__SHUTDOWN__"))) {
                    break;
                }

                // Log mtime in human readable form when storing to registry
                Instant lastModified = event.lastModified();
                LocalDateTime localDateTime = LocalDateTime.ofInstant(lastModified, ZoneId.systemDefault());
                DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
                String formattedTime = localDateTime.format(formatter);
                log.debug("Storing file to registry with mtime: {} ({} bytes, mtime: {})", event.path(), event.size(), formattedTime);

                registry.registerScannedFile(
                        event,
                        scanId
                );
            }
        } catch (InterruptedException e) {
            // normal shutdown signal
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            // log unexpected failure
            log.error("Error in registry ingest worker", e);
        }
    }


}

