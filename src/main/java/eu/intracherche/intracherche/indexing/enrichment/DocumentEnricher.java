package eu.intracherche.intracherche.indexing.enrichment;

import eu.intracherche.intracherche.indexing.model.IndexedDocument;
import eu.intracherche.intracherche.indexing.pipeline.PipelineContext;

import java.io.IOException;

/**
 * Stage 4 – Enrichment (optional but typical)
 *
 * Common enterprise enrichments:
 *
 * Language detection
 *
 * Content fingerprinting / checksum
 *
 * Entity extraction
 *
 * ACL resolution
 *
 * MIME-based boosts
 */
public interface DocumentEnricher {
    // Interface for document enrichment
    void enrich(PipelineContext ctx) throws IOException;

}
