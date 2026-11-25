package eu.intracherche.intracherche.discovery;

import eu.intracherche.intracherche.config.IndexedDirConfig;
import eu.intracherche.intracherche.config.MountType;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A share that cannot be opened has to fail the cycle. While the failure was swallowed
 * here, the caller went on to clean stalled files against a scan that never ran and the
 * admin panel reported every file up to date.
 */
class DiscoveryRunnerTest {

    @Test
    void a_scan_that_fails_fails_the_run() {
        DiscoveryRunner runner = new DiscoveryRunner(
                scannerThrowing(new IOException("SMB session open failed after 3 attempts")),
                smbDir("192.0.2.21", "Demo_tuto_Youtube"));

        RuntimeException thrown = assertThrows(RuntimeException.class, runner::run);
        assertTrue(thrown.getMessage().contains("192.0.2.21"), "the source is named: " + thrown.getMessage());
        assertEquals("SMB session open failed after 3 attempts", thrown.getCause().getMessage());
    }

    /** A pause or a shutdown is not the source's fault and leaves no problem behind. */
    @Test
    void an_interrupted_scan_ends_quietly() {
        DiscoveryRunner runner = new DiscoveryRunner(
                scannerThrowing(new InterruptedException("stopped")),
                smbDir("192.0.2.21", "Demo_tuto_Youtube"));

        runner.run();

        assertTrue(Thread.interrupted(), "the interrupted status is restored (and cleared here)");
    }

    @Test
    void a_scan_that_works_throws_nothing() {
        DiscoveryRunner runner = new DiscoveryRunner(scannerThrowing(null), smbDir("srv", "share"));

        runner.run();

        assertFalse(Thread.currentThread().isInterrupted());
    }

    // ── Fixtures ─────────────────────────────────────────────────────────────

    private static AbstractScanner scannerThrowing(Exception failure) {
        return new AbstractScanner(null) {
            @Override
            public void scan(IndexedDirConfig config) throws Exception {
                if (failure != null) throw failure;
            }
        };
    }

    private static IndexedDirConfig smbDir(String server, String share) {
        IndexedDirConfig dir = new IndexedDirConfig();
        dir.setMountType(MountType.SMB);
        dir.setSmbServer(server);
        dir.setSmbShare(share);
        return dir;
    }
}
