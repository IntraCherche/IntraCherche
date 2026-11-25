package eu.intracherche.intracherche.services;

import com.hierynomus.protocol.transport.TransportException;
import com.hierynomus.smbj.common.SMBRuntimeException;
import com.hierynomus.smbj.share.DiskShare;
import eu.intracherche.intracherche.common.states.IndexingState;
import eu.intracherche.intracherche.config.IndexesConfig;
import eu.intracherche.intracherche.discovery.SmbClientManager;
import eu.intracherche.intracherche.indexing.worker.IndexingProgressTracker;
import eu.intracherche.intracherche.registry.FileRecord;
import eu.intracherche.intracherche.registry.FileRegistry;
import org.junit.jupiter.api.Test;

import java.net.SocketException;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The laptop slept from midnight to 5 a.m. (Sept. 2026) with 20k Wikipedia files pending on
 * the Win 11 VM's share. On wake-up every file failed on the dead SMB connection, each one
 * marked FAILED with an error row: 2,000+ "erreurs" in half an hour, and counting.
 * <p>
 * A connection that stays broken must stop the cycle after a handful of files, leaving the
 * rest pending and untouched, and the breakage must be reported so the next cycle reconnects.
 */
class FileIndexingSmbOutageTest {

    private static final String HOST  = "198.51.100.21";
    private static final String SHARE = "wikipedia-20k";
    private static final int PENDING  = 300;

    private static FileRecord smbFile(int i) {
        return new FileRecord("id-" + i, "00\\32\\Article " + i + ".pdf", 0L, 1000L, null,
                IndexingState.DISCOVERED, 0L, true, SHARE, HOST,
                List.of(), List.of("Index_Wikipedia_20k"), null, null, null, null);
    }

    @Test
    void aShareThatStaysDownStopsTheCycleInsteadOfFailingEveryFile() throws Exception {
        FileRegistry registry = mock(FileRegistry.class);
        when(registry.findFilesToIndex())
                .thenReturn(IntStream.range(0, PENDING).mapToObj(FileIndexingSmbOutageTest::smbFile).toList());
        IndexesConfig indexesConfig = mock(IndexesConfig.class);
        when(indexesConfig.getIndexes()).thenReturn(List.of());

        DiskShare deadShare = mock(DiskShare.class);
        when(deadShare.openFile(any(), any(), any(), any(), any(), any()))
                .thenThrow(new SMBRuntimeException(
                        new TransportException(new SocketException("Relais brisé (pipe)"))));
        SmbClientManager smb = mock(SmbClientManager.class);
        when(smb.getShare(HOST, SHARE)).thenReturn(deadShare);

        FileIndexingService service = new FileIndexingService(registry, indexesConfig, null, null, smb,
                null, null, null, null, null, null, mock(IndexingProgressTracker.class), null);
        try {
            service.processFilesToIndex();
        } finally {
            service.tearDown();
        }

        // Four threads: at most the threshold, plus the three files already in flight when it trips.
        int maxAttempted = FileIndexingService.SMB_OUTAGE_THRESHOLD + 3;
        verify(registry, atMost(maxAttempted)).markFailed(anyString());
        long attempted = mockingDetails(smb).getInvocations().stream()
                .filter(inv -> inv.getMethod().getName().equals("getShare")).count();
        assertThat(attempted).isLessThanOrEqualTo(maxAttempted);
        verify(smb, atLeastOnce()).reportBrokenShare(eq(HOST), eq(SHARE), eq(deadShare));
    }

    @Test
    void connectionFailuresAreTransient() {
        assertThat(FileIndexingService.isTransientSmb(new SMBRuntimeException(
                new TransportException(new SocketException("Relais brisé (pipe)"))))).isTrue();
        assertThat(FileIndexingService.isTransientSmb(new SMBRuntimeException(
                "Not enough credits (0 available) to hand out 1 sequence numbers"))).isTrue();
        // Wrapped once more, as the read path hands it on.
        assertThat(FileIndexingService.isTransientSmb(new java.io.IOException("read failed",
                new TransportException(new SocketException("Broken pipe"))))).isTrue();
    }

    @Test
    void aBrokenFileIsNotAConnectionFailure() {
        assertThat(FileIndexingService.isTransientSmb(
                new java.io.IOException("Parsing impossible: Failed to parse document"))).isFalse();
    }
}
