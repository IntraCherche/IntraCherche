package eu.intracherche.intracherche.indexing.pipeline;

import eu.intracherche.intracherche.indexing.error.IndexingException;

import java.io.IOException;

/**
 * Stage 1 – Pre-processing / validation
 * Before parsing:
 *
 * File exists
 *
 * File readable
 *
 * Not already indexed with same checksum
 *
 * File size limits
 *
 * File type allowed
 *
 * This stage is cheap and prevents wasted work.
 */
public interface IndexingStage {

    void execute(PipelineContext context) throws IndexingException;
}