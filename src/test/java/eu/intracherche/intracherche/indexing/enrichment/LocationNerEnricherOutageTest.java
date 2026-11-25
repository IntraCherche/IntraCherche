package eu.intracherche.intracherche.indexing.enrichment;

import eu.intracherche.intracherche.config.NerConfig;
import eu.intracherche.intracherche.indexing.model.IndexedDocument;
import eu.intracherche.intracherche.indexing.outage.ServiceOutageMonitor;
import eu.intracherche.intracherche.indexing.outage.ServiceOutageMonitor.Dependency;
import eu.intracherche.intracherche.indexing.parser.IndexedSection;
import eu.intracherche.intracherche.indexing.parser.ParseResult;
import eu.intracherche.intracherche.indexing.pipeline.PipelineContext;
import eu.intracherche.intracherche.services.NerService;
import eu.intracherche.intracherche.services.NerService.GeolocationsResult;
import org.apache.tika.metadata.Metadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** A document indexed while NER is down would never get its place names. */
class LocationNerEnricherOutageTest {

    private static final GeolocationsResult LYON =
            new GeolocationsResult(List.of("Lyon"), List.of(), List.of(), List.of());

    private NerService nerService;
    private ServiceOutageMonitor monitor;
    private LocationNerEnricher enricher;

    @BeforeEach
    void setUp() {
        nerService = mock(NerService.class);
        monitor = mock(ServiceOutageMonitor.class);
        enricher = new LocationNerEnricher(nerService, new NerConfig(), monitor, () -> false);
    }

    private static PipelineContext context() {
        PipelineContext ctx = new PipelineContext(Path.of("doc.pdf"), null);
        ctx.setParseResult(new ParseResult(List.of(new IndexedSection("Page 1", "Réunion à Lyon")),
                new Metadata(), "application/pdf"));
        ctx.setIndexedDocument(new IndexedDocument("doc1", Path.of("doc.pdf"), "Réunion à Lyon",
                new Metadata(), Set.of(), Set.of(), null, null, null, null));
        return ctx;
    }

    @Test
    void outage_waits_for_the_service_then_extracts_the_place_names() throws Exception {
        when(monitor.confirmOutage(Dependency.NER)).thenReturn(true);
        when(nerService.extractGeolocations(anyString()))
                .thenThrow(new ResourceAccessException("Connection refused"))
                .thenReturn(LYON);
        PipelineContext ctx = context();

        enricher.enrich(ctx);

        verify(monitor).awaitRecovery(eq(Dependency.NER), any());
        assertThat(ctx.indexedDocument().metadata().getValues("geo.cities")).containsExactly("Lyon");
    }

    // A text the service fails on while it answers: skipped, as before — no endless wait.
    @Test
    void failure_while_the_service_answers_skips_the_section() throws Exception {
        when(monitor.confirmOutage(Dependency.NER)).thenReturn(false);
        when(nerService.extractGeolocations(anyString())).thenThrow(new ResourceAccessException("500"));
        PipelineContext ctx = context();

        enricher.enrich(ctx);

        verify(monitor, never()).awaitRecovery(any(), any());
        assertThat(ctx.indexedDocument().metadata().getValues("geo.cities")).isEmpty();
    }
}
