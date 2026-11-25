package eu.intracherche.intracherche.indexing.pipeline;

import eu.intracherche.intracherche.indexing.error.IndexingException;
import eu.intracherche.intracherche.indexing.parser.DocumentParser;
import eu.intracherche.intracherche.indexing.parser.ParseResult;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;

public final class ParsingStage implements IndexingStage {

    private final DocumentParser parser;

    public ParsingStage(DocumentParser parser) {
        this.parser = parser;
    }

    @Override
    public void execute(PipelineContext context) throws IndexingException {
        if (context.getContentBytes() == null) {
            throw new IndexingException("No content available in context for: " + context.path());
        }

        try {
            Metadata metadata = new Metadata();
            metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, context.path().getFileName().toString());

            ParseResult result = parser.parse(context.getContentBytes(), metadata);

            context.setParseResult(result);
        } catch (Exception e) {
            throw new IndexingException("Tika parsing failed for " + context.path(), e);
        }
    }
}

