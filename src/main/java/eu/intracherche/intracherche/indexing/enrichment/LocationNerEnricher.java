package eu.intracherche.intracherche.indexing.enrichment;

import eu.intracherche.intracherche.common.constants.LocationMetadataConstants;
import eu.intracherche.intracherche.config.NerConfig;
import eu.intracherche.intracherche.indexing.model.IndexedDocument;
import eu.intracherche.intracherche.indexing.outage.ServiceOutageMonitor;
import eu.intracherche.intracherche.indexing.outage.ServiceOutageMonitor.Dependency;
import eu.intracherche.intracherche.indexing.parser.IndexedSection;
import eu.intracherche.intracherche.indexing.pipeline.PipelineContext;
import eu.intracherche.intracherche.services.NerService;
import eu.intracherche.intracherche.services.NerService.GeolocationsResult;
import org.apache.tika.metadata.Metadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

/**
 * Named Entity Recognition (NER) enricher for geographical location extraction.
 * Calls the Python NER service and populates geolocation_city / geolocation_dpt /
 * geolocation_region / geolocation_country fields on each indexed document.
 *
 * Each section (page) is sent to the NER service independently so that no page is
 * skipped due to the service-side character limit. Results are merged and stored at
 * the document level; IndexWorker then stamps the same geolocation metadata onto
 * every page's Solr document.
 *
 * When the service is down, the enricher waits for it and runs again (see
 * {@link ServiceOutageMonitor}): a document indexed without its place names would never get
 * them. A section the service fails on while it answers is skipped, as before.
 */
public class LocationNerEnricher implements DocumentEnricher {

    private static final Logger log = LoggerFactory.getLogger(LocationNerEnricher.class);

    private final NerService nerService;
    private final NerConfig nerConfig;
    /** Null: no waiting, failed sections are skipped (benchmark). */
    private final ServiceOutageMonitor outageMonitor;
    private final BooleanSupplier stopWaiting;

    public LocationNerEnricher(NerService nerService, NerConfig nerConfig) {
        this(nerService, nerConfig, null, () -> true);
    }

    /** @param stopWaiting true when a wait for the service must end (admin pause) */
    public LocationNerEnricher(NerService nerService, NerConfig nerConfig,
                               ServiceOutageMonitor outageMonitor, BooleanSupplier stopWaiting) {
        this.nerService    = nerService;
        this.nerConfig     = nerConfig;
        this.outageMonitor = outageMonitor;
        this.stopWaiting   = stopWaiting;
    }

    @Override
    public void enrich(PipelineContext ctx) throws IOException {
        if (ctx == null) return;

        IndexedDocument doc = ctx.indexedDocument();
        if (doc == null) return;

        List<String> sectionTexts = getSectionTexts(ctx);
        if (sectionTexts.isEmpty()) return;

        GeolocationsResult merged;
        while ((merged = mergeAcrossSections(doc.id(), sectionTexts)) == null) {
            outageMonitor.awaitRecovery(Dependency.NER, stopWaiting);
        }
        if (merged.isEmpty()) return;

        Metadata newMetadata = new Metadata();
        if (doc.metadata() != null) {
            for (String name : doc.metadata().names()) {
                for (String value : doc.metadata().getValues(name)) {
                    newMetadata.add(name, value);
                }
            }
        }

        addAll(newMetadata, LocationMetadataConstants.GEO_CITIES.getKey(),      merged.cities());
        addAll(newMetadata, LocationMetadataConstants.GEO_DEPARTMENTS.getKey(), merged.departments());
        addAll(newMetadata, LocationMetadataConstants.GEO_REGIONS.getKey(),     merged.regions());
        addAll(newMetadata, LocationMetadataConstants.GEO_COUNTRIES.getKey(),   merged.countries());

        log.debug("NER [{}] cities={} dpts={} regions={} countries={}",
                doc.id(), merged.cities(), merged.departments(), merged.regions(), merged.countries());

        Set<String> accessTokens = doc.accessTokens() != null ? doc.accessTokens() : Set.of();
        ctx.setIndexedDocument(new IndexedDocument(
                doc.id(), doc.path(), doc.content(), newMetadata,
                accessTokens, doc.denyTokens(), doc.indexSource(), doc.mtime(),
                doc.smbServer(), doc.smbShare()
        ));
    }

    /**
     * Calls the NER service once per section, counts how many sections each entity
     * appears in, then returns the top-N entities per bucket sorted by frequency.
     * Entities mentioned across more pages are more likely to be central to the document.
     * Returns null when a call failed because the service is down: the caller waits and retries.
     */
    private GeolocationsResult mergeAcrossSections(String docId, List<String> sectionTexts) {
        // Fire all NER calls concurrently — the NER service may have multiple workers
        List<CompletableFuture<GeolocationsResult>> futures;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            futures = sectionTexts.stream()
                .map(text -> CompletableFuture.supplyAsync(
                    () -> nerService.extractGeolocations(text), executor))
                .collect(Collectors.toList());
        }

        List<GeolocationsResult> results = new ArrayList<>(futures.size());
        int failed = 0;
        String firstError = null;
        for (var future : futures) {
            try {
                results.add(future.join());
            } catch (CompletionException e) {
                failed++;
                if (firstError == null) firstError = String.valueOf((e.getCause() != null ? e.getCause() : e).getMessage());
            }
        }
        if (failed > 0) {
            if (outageMonitor != null && outageMonitor.confirmOutage(Dependency.NER)) return null;
            log.warn("NER failed on {}/{} section(s) of document {} ({}): their place names are skipped",
                    failed, sectionTexts.size(), docId, firstError);
        }

        Map<String, Integer> cityCounts    = new LinkedHashMap<>();
        Map<String, Integer> deptCounts    = new LinkedHashMap<>();
        Map<String, Integer> regionCounts  = new LinkedHashMap<>();
        Map<String, Integer> countryCounts = new LinkedHashMap<>();

        for (GeolocationsResult geo : results) {
            geo.cities().forEach(c      -> cityCounts.merge(c, 1, Integer::sum));
            geo.departments().forEach(d -> deptCounts.merge(d, 1, Integer::sum));
            geo.regions().forEach(r     -> regionCounts.merge(r, 1, Integer::sum));
            geo.countries().forEach(c   -> countryCounts.merge(c, 1, Integer::sum));
        }

        log.debug("NER [{}] processed {} sections", docId, sectionTexts.size());
        return new GeolocationsResult(
                rankAndCap(cityCounts,    nerConfig.getGeoCapCities()),
                rankAndCap(deptCounts,    nerConfig.getGeoCapDepartments()),
                rankAndCap(regionCounts,  nerConfig.getGeoCapRegions()),
                rankAndCap(countryCounts, nerConfig.getGeoCapCountries())
        );
    }

    private static List<String> rankAndCap(Map<String, Integer> counts, int cap) {
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .map(Map.Entry::getKey)
                .limit(cap)
                .collect(Collectors.toList());
    }

    /** Returns one entry per non-blank section; falls back to doc.content() for single-string callers. */
    private static List<String> getSectionTexts(PipelineContext ctx) {
        if (ctx.parseResult() != null && !ctx.parseResult().getSections().isEmpty()) {
            return ctx.parseResult().getSections().stream()
                    .map(IndexedSection::content)
                    .filter(c -> c != null && !c.isBlank())
                    .collect(Collectors.toList());
        }
        String content = ctx.indexedDocument().content();
        return (content != null && !content.isBlank()) ? List.of(content) : List.of();
    }

    private static void addAll(Metadata metadata, String key, List<String> values) {
        for (String value : values) {
            metadata.add(key, value);
        }
    }
}
