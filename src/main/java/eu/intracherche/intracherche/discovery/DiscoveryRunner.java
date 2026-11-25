package eu.intracherche.intracherche.discovery;

import eu.intracherche.intracherche.config.IndexedDirConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * This class:
 *
 * Starts scanners
 *
 * Applies configuration
 *
 * Manages thread pools
 *
 * Emits events
 */
public class DiscoveryRunner implements Runnable {

    private final IndexedDirConfig dirConfig;

    private final AbstractScanner scanner;

    private static final Logger log =
            LoggerFactory.getLogger(DiscoveryRunner.class);

    public DiscoveryRunner(AbstractScanner scanner, IndexedDirConfig dirConfig) {
        this.scanner = scanner;
        this.dirConfig = dirConfig;

    }

    @Override
    public void run() {
        try {
            scanner.scan(dirConfig); // This may throw IOException or InterruptedException
        } catch (InterruptedException e) {
            // A pause or a shutdown, not a fault of the source: it ends the scan quietly,
            // exactly as before, and leaves no problem behind in the admin panel.
            log.error("Directory scan interrupted during scan of {}", sourceLabel(), e);
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            // A scan that could not run has to fail the cycle. Swallowing it here — which
            // is what an unreachable share used to get — let the caller treat a share it
            // never opened as an empty one: stalled-file cleanup ran on a scan that had
            // not happened, indexing ran on nothing, and the admin panel reported every
            // file up to date. ApplicationOrchestrator now records it against the source.
            throw new RuntimeException("Error during directory scan of " + sourceLabel(), e);
        }

    }

    /** The source as an admin knows it: the SMB server, or the local path. */
    private String sourceLabel() {
        return dirConfig.getSmbServer();
    }

}


