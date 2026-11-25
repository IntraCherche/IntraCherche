package eu.intracherche.intracherche.indexing.pipeline;

import eu.intracherche.intracherche.indexing.model.IndexedDocument;
import eu.intracherche.intracherche.indexing.parser.ParseResult;

import java.nio.file.Path;
import java.util.List;

public final class PipelineContext {

    private final Path relativePath;
    private final byte[] contentBytes;
    private ParseResult parseResult;
    private IndexedDocument indexedDocument;
    /** One vector per section, in the same order as parseResult.getSections(). Null entries mean embedding failed. */
    private List<float[]> sectionVectors;

    public PipelineContext(Path relativePath, byte[] contentBytes) {
        this.relativePath = relativePath;
        this.contentBytes = contentBytes;
    }

    public Path path() { return relativePath; }
    public byte[] getContentBytes() { return contentBytes; }

    public ParseResult parseResult() { return parseResult; }
    public void setParseResult(ParseResult result) {
        this.parseResult = result;
    }

    public IndexedDocument indexedDocument() {
        return indexedDocument;
    }
    public void setIndexedDocument(IndexedDocument doc) {
        this.indexedDocument = doc;
    }

    public List<float[]> getSectionVectors() { return sectionVectors; }
    public void setSectionVectors(List<float[]> vectors) { this.sectionVectors = vectors; }
}

