package eu.intracherche.intracherche.services;

import eu.intracherche.intracherche.common.events.FileDiscoveredEvent;
import eu.intracherche.intracherche.config.IndexedDirConfig;
import eu.intracherche.intracherche.config.MountType;
import eu.intracherche.intracherche.discovery.AbstractScanner;
import eu.intracherche.intracherche.queues.DiscoveryQueue;
import eu.intracherche.intracherche.registry.FileRegistry;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * The scanner fills the discovery queue far faster than the registry is written, so a
 * large share ends its scan with a full queue still to drain. A fixed 30 s deadline on
 * that drain failed \\198.51.100.21\wikipedia-20k every cycle, and the panel reported
 * the share as unreachable while it had been read in full.
 */
class FileRegistryFeedingServiceTest {

    private static final int STALL_SECONDS = 1;

    /** Draining takes several stall windows, but the worker never stops: that is success. */
    @Test
    void a_slow_drain_that_keeps_progressing_completes() throws Exception {
        DiscoveryQueue queue = new DiscoveryQueue(1_000);
        AtomicInteger registered = new AtomicInteger();
        FileRegistry registry = mock(FileRegistry.class);
        doAnswer(inv -> {
            Thread.sleep(10);
            registered.incrementAndGet();
            return null;
        }).when(registry).registerScannedFile(any(), anyLong());

        // 300 files at 10 ms is ~3 s of draining against a 1 s stall window
        FileRegistryFeedingService service = service(scannerPublishing(queue, 300), registry, queue);
        try {
            service.startFeeding(smbDir());
        } finally {
            service.shutdown();
        }

        assertEquals(300, registered.get());
    }

    /** A worker that stops writing fails the source, and leaves nothing for the next scan. */
    @Test
    void a_stuck_worker_fails_and_empties_the_queue() throws Exception {
        DiscoveryQueue queue = new DiscoveryQueue(1_000);
        CountDownLatch never = new CountDownLatch(1);
        FileRegistry registry = mock(FileRegistry.class);
        doAnswer(inv -> {
            never.await();
            return null;
        }).when(registry).registerScannedFile(any(), anyLong());

        FileRegistryFeedingService service = service(scannerPublishing(queue, 50), registry, queue);
        IOException thrown;
        try {
            thrown = assertThrows(IOException.class, () -> service.startFeeding(smbDir()));
        } finally {
            service.shutdown();
        }

        assertFalse(thrown instanceof FileRegistryFeedingService.SourceUnreachableException,
                "the source was read: it is not reported unreachable");
        assertTrue(thrown.getMessage().contains("bloqué"), thrown.getMessage());
        assertEquals(0, queue.size(), "no event, and no shutdown sentinel, is left for the next scan");
    }

    @Test
    void a_scan_that_fails_is_an_unreachable_source() {
        DiscoveryQueue queue = new DiscoveryQueue(10);
        AbstractScanner failing = new AbstractScanner(queue) {
            @Override
            public void scan(IndexedDirConfig config) throws Exception {
                throw new IOException("SMB session open failed after 3 attempts");
            }
        };

        FileRegistryFeedingService service = service(failing, mock(FileRegistry.class), queue);
        try {
            IOException thrown = assertThrows(IOException.class, () -> service.startFeeding(smbDir()));
            assertInstanceOf(FileRegistryFeedingService.SourceUnreachableException.class, thrown);
        } finally {
            service.shutdown();
        }
    }

    // ── Fixtures ─────────────────────────────────────────────────────────────

    private static FileRegistryFeedingService service(AbstractScanner scanner, FileRegistry registry, DiscoveryQueue queue) {
        return new FileRegistryFeedingService(scanner, registry, queue, null, STALL_SECONDS);
    }

    private static AbstractScanner scannerPublishing(DiscoveryQueue queue, int files) {
        return new AbstractScanner(queue) {
            @Override
            public void scan(IndexedDirConfig config) throws Exception {
                for (int i = 0; i < files; i++) {
                    discoveryQueue.publish(new FileDiscoveredEvent(
                            Path.of("dir", "file" + i + ".pdf"), "share", "srv", 1000L,
                            Instant.EPOCH, Instant.now(),
                            Collections.emptySet(), Collections.emptySet(), "", true));
                }
            }
        };
    }

    private static IndexedDirConfig smbDir() {
        IndexedDirConfig dir = new IndexedDirConfig();
        dir.setMountType(MountType.SMB);
        dir.setSmbServer("srv");
        dir.setSmbShare("share");
        return dir;
    }
}
