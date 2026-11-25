package eu.intracherche.intracherche.common;

/** Thrown when free-text search input contains Solr query syntax this app never intends to let
 *  users reach directly (see {@link SolrQuerySyntaxGuard}). */
public class InvalidSearchQueryException extends RuntimeException {
    public InvalidSearchQueryException(String message) {
        super(message);
    }
}
