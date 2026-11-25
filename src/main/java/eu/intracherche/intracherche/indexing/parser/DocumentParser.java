package eu.intracherche.intracherche.indexing.parser;

import org.apache.tika.metadata.Metadata;

import java.io.IOException;

/**
 * Stage 2 – Parsing / content extraction
 *
 * This is not Solr-specific and should be reusable.
 */
public interface DocumentParser {
    ParseResult parse(byte[] data, Metadata metadata) throws IOException;
}
