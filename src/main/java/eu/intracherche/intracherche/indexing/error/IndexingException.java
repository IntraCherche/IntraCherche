package eu.intracherche.intracherche.indexing.error;

public class IndexingException extends Exception {
    public IndexingException(String message) {
        super(message);
    }
    
    public IndexingException(String message, Throwable cause) {
        super(message, cause);
    }
}
