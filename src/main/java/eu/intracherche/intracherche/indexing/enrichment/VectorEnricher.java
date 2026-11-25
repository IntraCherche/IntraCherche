package eu.intracherche.intracherche.indexing.enrichment;

import eu.intracherche.intracherche.indexing.outage.ServiceOutageMonitor;
import eu.intracherche.intracherche.indexing.outage.ServiceOutageMonitor.Dependency;
import eu.intracherche.intracherche.indexing.parser.IndexedSection;
import eu.intracherche.intracherche.indexing.pipeline.PipelineContext;
import eu.intracherche.intracherche.services.EmbeddingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Enriches each document section with a dense vector embedding for hybrid (BM25 + kNN) search.
 * Null entries in the result list are blank sections, which have nothing to embed.
 *
 * <p>A section with text left without a vector would stay out of the kNN leg for good: the file
 * is marked indexed all the same. When the service is down, the enricher waits for it and embeds
 * the missing sections then (see {@link ServiceOutageMonitor}); when it answers but still fails,
 * the file fails and is retried next cycle.</p>
 */
public class VectorEnricher implements DocumentEnricher {

    private static final Logger log = LoggerFactory.getLogger(VectorEnricher.class);
    // Small chunks keep per-call JSON payload manageable and avoid memory pressure on the Python side
    private static final int EMBED_CHUNK_SIZE = 8;

    private final EmbeddingService embeddingService;
    /** Null: no waiting, a missing vector fails the file at once (benchmark). */
    private final ServiceOutageMonitor outageMonitor;
    private final BooleanSupplier stopWaiting;

    public VectorEnricher(EmbeddingService embeddingService) {
        this(embeddingService, null, () -> true);
    }

    /** @param stopWaiting true when a wait for the service must end (admin pause) */
    public VectorEnricher(EmbeddingService embeddingService, ServiceOutageMonitor outageMonitor,
                          BooleanSupplier stopWaiting) {
        this.embeddingService = embeddingService;
        this.outageMonitor = outageMonitor;
        this.stopWaiting = stopWaiting;
    }

    @Override
    public void enrich(PipelineContext ctx) throws IOException {
        if (!embeddingService.isEnabled()) return;

        List<IndexedSection> sections = ctx.parseResult().getSections();
        if (sections == null || sections.isEmpty()) return;

        List<String> contents = sections.stream()
                .map(IndexedSection::content)
                .collect(Collectors.toList());

        float[][] resultArray = new float[contents.size()][];
        List<Integer> missing = IntStream.range(0, contents.size()).boxed().toList();
        while (true) {
            embedInto(resultArray, contents, missing);
            missing = IntStream.range(0, contents.size())
                    .filter(i -> resultArray[i] == null && contents.get(i) != null && !contents.get(i).isBlank())
                    .boxed().toList();
            if (missing.isEmpty()) break;
            if (outageMonitor == null || !outageMonitor.confirmOutage(Dependency.EMBEDDING)) {
                throw new IOException("Embedding impossible : " + missing.size() + "/" + contents.size()
                        + " section(s) sans vecteur, fichier réessayé au prochain cycle");
            }
            outageMonitor.awaitRecovery(Dependency.EMBEDDING, stopWaiting);
        }

        List<float[]> vectors = new ArrayList<>(Arrays.asList(resultArray));
        ctx.setSectionVectors(vectors);
        log.debug("Embedded {}/{} sections", vectors.stream().filter(Objects::nonNull).count(), sections.size());
    }

    /** Embeds the sections at {@code indices}, in chunks sent concurrently, into {@code result}. */
    private void embedInto(float[][] result, List<String> contents, List<Integer> indices) {
        record Chunk(List<Integer> indices, CompletableFuture<List<float[]>> future) {}
        List<Chunk> chunks = new ArrayList<>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int start = 0; start < indices.size(); start += EMBED_CHUNK_SIZE) {
                List<Integer> chunkIndices = indices.subList(start, Math.min(start + EMBED_CHUNK_SIZE, indices.size()));
                List<String> texts = chunkIndices.stream().map(contents::get).toList();
                chunks.add(new Chunk(chunkIndices,
                    CompletableFuture.supplyAsync(() -> embeddingService.embedBatch(texts, false), executor)));
            }
        }

        // Collect results in original section order, falling back per-section on chunk failure
        for (Chunk chunk : chunks) {
            List<float[]> chunkVectors = chunk.future().join();
            for (int j = 0; j < chunk.indices().size(); j++) {
                int i = chunk.indices().get(j);
                result[i] = chunkVectors.isEmpty() ? embeddingService.embed(contents.get(i), false) : chunkVectors.get(j);
            }
        }
    }
}
