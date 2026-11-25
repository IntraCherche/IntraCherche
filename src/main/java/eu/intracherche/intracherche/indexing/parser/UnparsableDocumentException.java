package eu.intracherche.intracherche.indexing.parser;

import java.io.IOException;

/**
 * The document itself cannot be read (empty, corrupt, refused by a parser safeguard), as opposed to a
 * failure around it (service down, network, memory). Reading it again gives the same result until the
 * file changes, so the indexer does not retry it every cycle.
 */
public class UnparsableDocumentException extends IOException {

    public UnparsableDocumentException(String message, Throwable cause) {
        super(message, cause);
    }
}
