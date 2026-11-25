package eu.intracherche.intracherche.indexing.pipeline;

import eu.intracherche.intracherche.indexing.enrichment.DocumentEnricher;
import eu.intracherche.intracherche.indexing.error.IndexingException;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Stage 4 – Enrichment
 *
 * Sequential enrichers run first in order (e.g. LanguageDetector).
 * Parallel enrichers (NER + Embedding) then run concurrently on virtual threads.
 */
public final class EnrichmentStage implements IndexingStage {

    private static final Executor ASYNC_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    private final List<DocumentEnricher> sequentialEnrichers;
    private final List<DocumentEnricher> parallelEnrichers;

    public EnrichmentStage(List<DocumentEnricher> sequentialEnrichers, List<DocumentEnricher> parallelEnrichers) {
        this.sequentialEnrichers = sequentialEnrichers;
        this.parallelEnrichers = parallelEnrichers;
    }

    @Override
    public void execute(PipelineContext context) throws IndexingException {
        try {
            for (DocumentEnricher enricher : sequentialEnrichers) {
                enricher.enrich(context);
            }

            if (parallelEnrichers.isEmpty()) return;

            CompletableFuture<?>[] futures = parallelEnrichers.stream()
                    .map(enricher -> CompletableFuture.runAsync(() -> {
                        try {
                            enricher.enrich(context);
                        } catch (IOException e) {
                            throw new CompletionException(e);
                        }
                    }, ASYNC_EXECUTOR))
                    .toArray(CompletableFuture[]::new);

            CompletableFuture.allOf(futures).join();

        } catch (CompletionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new IndexingException("Enrichment failed: " + cause.getMessage(), cause);
        } catch (IOException e) {
            throw new IndexingException("Enrichment failed: " + e.getMessage(), e);
        }
    }
}
