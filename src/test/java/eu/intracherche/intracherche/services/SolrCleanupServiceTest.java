package eu.intracherche.intracherche.services;

import eu.intracherche.intracherche.registry.FileRegistry;
import org.apache.solr.client.solrj.SolrClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SolrCleanupServiceTest {

    private static final String CORE = "test-core";
    private static final String SHARE = "test-share";

    @Mock SolrClient solrClient;
    @Mock FileRegistry fileRegistry;

    @InjectMocks SolrCleanupService service;

    @Test
    void stalled_files_are_removed_from_solr_and_registry() throws Exception {
        // 1 stalled out of 5 total → 20%, below the 30% safety threshold
        when(fileRegistry.countFilesByShare(SHARE)).thenReturn(5L);
        when(fileRegistry.countStalledFiles(eq(SHARE), anyLong())).thenReturn(1L);
        when(fileRegistry.getStalledSolrIds(eq(SHARE), anyLong(), anyInt(), anyInt()))
                .thenReturn(List.of("solr-id-1"));

        service.cleanStalledFiles(CORE, SHARE, 720, 30);

        verify(solrClient).deleteByQuery(eq(CORE), eq("parent_id:(solr-id-1)"), eq(1000));
        verify(fileRegistry).deleteStalledFiles(eq(SHARE), anyLong());
    }

    @Test
    void safety_valve_blocks_cleanup_when_too_many_files_are_stalled() throws Exception {
        // 4 stalled out of 5 total → 80%, above the 30% threshold
        when(fileRegistry.countFilesByShare(SHARE)).thenReturn(5L);
        when(fileRegistry.countStalledFiles(eq(SHARE), anyLong())).thenReturn(4L);

        service.cleanStalledFiles(CORE, SHARE, 720, 30);

        verify(solrClient, never()).deleteByQuery(any(), anyString(), anyInt());
        verify(fileRegistry, never()).deleteStalledFiles(any(), anyLong());
    }

    @Test
    void empty_share_skips_cleanup_entirely() throws Exception {
        when(fileRegistry.countFilesByShare(SHARE)).thenReturn(0L);
        when(fileRegistry.countStalledFiles(eq(SHARE), anyLong())).thenReturn(0L);

        service.cleanStalledFiles(CORE, SHARE, 720, 30);

        verify(solrClient, never()).deleteByQuery(any(), anyString(), anyInt());
        verify(fileRegistry, never()).deleteStalledFiles(any(), anyLong());
    }

    @Test
    void no_stalled_files_does_nothing() throws Exception {
        when(fileRegistry.countFilesByShare(SHARE)).thenReturn(5L);
        when(fileRegistry.countStalledFiles(eq(SHARE), anyLong())).thenReturn(0L);

        service.cleanStalledFiles(CORE, SHARE, 720, 30);

        verify(solrClient, never()).deleteByQuery(any(), anyString(), anyInt());
        verify(fileRegistry, never()).deleteStalledFiles(any(), anyLong());
    }

    @Test
    void threshold_converts_hours_to_milliseconds() throws Exception {
        // Capture the threshold passed to countStalledFiles and verify it is
        // ~3600 seconds in the past, not ~60 seconds (which would mean minutes were used).
        when(fileRegistry.countFilesByShare(SHARE)).thenReturn(5L);
        when(fileRegistry.countStalledFiles(eq(SHARE), anyLong())).thenReturn(0L);

        long before = System.currentTimeMillis();
        service.cleanStalledFiles(CORE, SHARE, 1, 30);
        long after = System.currentTimeMillis();

        var captor = org.mockito.ArgumentCaptor.forClass(Long.class);
        verify(fileRegistry).countStalledFiles(eq(SHARE), captor.capture());

        long capturedThreshold = captor.getValue();
        // threshold must be ~1h in the past, not ~1min (which would happen if hours were treated as minutes)
        assertThat(capturedThreshold).isBetween(before - 3600000L, after - 3600000L);
    }

    @Test
    void a_large_cleanup_is_split_so_no_query_exceeds_solrs_boolean_clause_limit() throws Exception {
        when(fileRegistry.countFilesByShare(SHARE)).thenReturn(10_000L);
        when(fileRegistry.countStalledFiles(eq(SHARE), anyLong())).thenReturn(2128L);
        when(fileRegistry.getStalledSolrIds(eq(SHARE), anyLong(), anyInt(), anyInt()))
                .thenAnswer(inv -> {
                    int limit = inv.getArgument(2);
                    int offset = inv.getArgument(3);
                    int n = Math.max(0, Math.min(limit, 2128 - offset));
                    return java.util.stream.IntStream.range(offset, offset + n)
                            .mapToObj(i -> "id" + i).toList();
                });

        service.cleanStalledFiles(CORE, SHARE, 720, 30);

        var captor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(solrClient, atLeast(5)).deleteByQuery(eq(CORE), captor.capture(), eq(1000));
        int total = 0;
        for (String q : captor.getAllValues()) {
            int clauses = q.split(" OR ").length;
            assertThat(clauses).isLessThanOrEqualTo(1024);
            total += clauses;
        }
        assertThat(total).isEqualTo(2128);
        verify(fileRegistry).deleteStalledFiles(eq(SHARE), anyLong());
    }
}
