package eu.intracherche.intracherche.indexing.enrichment;

import eu.intracherche.intracherche.indexing.outage.ServiceOutageMonitor;
import eu.intracherche.intracherche.indexing.parser.IndexedSection;
import eu.intracherche.intracherche.indexing.parser.ParseResult;
import eu.intracherche.intracherche.indexing.pipeline.PipelineContext;
import eu.intracherche.intracherche.services.EmbeddingService;
import org.apache.tika.metadata.Metadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/**
 * A file indexed while the embedding service is down or still starting would be marked
 * indexed without its vectors and never embedded again: it must fail, and be retried.
 */
class VectorEnricherTest {

    private EmbeddingService embeddingService;
    private VectorEnricher enricher;

    @BeforeEach
    void setUp() {
        embeddingService = mock(EmbeddingService.class);
        when(embeddingService.isEnabled()).thenReturn(true);
        enricher = new VectorEnricher(embeddingService);
    }

    private static PipelineContext context(String... contents) {
        List<IndexedSection> sections = Arrays.stream(contents)
                .map(c -> new IndexedSection("Page", c))
                .toList();
        PipelineContext ctx = new PipelineContext(Path.of("doc.pdf"), null);
        ctx.setParseResult(new ParseResult(sections, new Metadata(), "application/pdf"));
        return ctx;
    }

    @Test
    void every_section_embedded_sets_the_vectors() throws Exception {
        float[] v1 = {0.1f}, v2 = {0.2f};
        when(embeddingService.embedBatch(anyList(), eq(false))).thenReturn(Arrays.asList(v1, v2));
        PipelineContext ctx = context("un", "deux");

        enricher.enrich(ctx);

        assertThat(ctx.getSectionVectors()).containsExactly(v1, v2);
    }

    @Test
    void service_down_fails_the_file() {
        when(embeddingService.embedBatch(anyList(), anyBoolean())).thenReturn(Collections.emptyList());
        when(embeddingService.embed(any(), anyBoolean())).thenReturn(null);
        PipelineContext ctx = context("un", "deux");

        assertThatThrownBy(() -> enricher.enrich(ctx))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("2/2");
        assertThat(ctx.getSectionVectors()).isNull();
    }

    // A blank section has nothing to embed: its null vector is not a failure.
    @Test
    void blank_section_without_vector_is_not_a_failure() throws Exception {
        float[] v1 = {0.1f};
        when(embeddingService.embedBatch(anyList(), eq(false))).thenReturn(Arrays.asList(v1, null));
        PipelineContext ctx = context("un", "  ");

        enricher.enrich(ctx);

        assertThat(ctx.getSectionVectors()).containsExactly(v1, null);
    }

    // Service down mid-file: the enricher waits for it, then embeds only what is missing.
    @Test
    void outage_waits_for_the_service_then_embeds_the_missing_sections() throws Exception {
        ServiceOutageMonitor monitor = mock(ServiceOutageMonitor.class);
        when(monitor.confirmOutage(ServiceOutageMonitor.Dependency.EMBEDDING)).thenReturn(true);
        float[] v1 = {0.1f}, v2 = {0.2f};
        when(embeddingService.embedBatch(anyList(), eq(false)))
                .thenReturn(Arrays.asList(v1, null))   // second section lost to the outage
                .thenReturn(List.of(v2));              // service back
        PipelineContext ctx = context("un", "deux");

        new VectorEnricher(embeddingService, monitor, () -> false).enrich(ctx);

        verify(monitor).awaitRecovery(eq(ServiceOutageMonitor.Dependency.EMBEDDING), any());
        verify(embeddingService).embedBatch(List.of("deux"), false);
        assertThat(ctx.getSectionVectors()).containsExactly(v1, v2);
    }

    // The service answers but fails anyway: no wait, the file fails as without a monitor.
    @Test
    void failure_while_the_service_answers_fails_the_file() throws Exception {
        ServiceOutageMonitor monitor = mock(ServiceOutageMonitor.class);
        when(monitor.confirmOutage(any())).thenReturn(false);
        when(embeddingService.embedBatch(anyList(), anyBoolean())).thenReturn(Collections.emptyList());

        assertThatThrownBy(() -> new VectorEnricher(embeddingService, monitor, () -> false).enrich(context("un")))
                .isInstanceOf(IOException.class);
        verify(monitor, never()).awaitRecovery(any(), any());
    }
}
