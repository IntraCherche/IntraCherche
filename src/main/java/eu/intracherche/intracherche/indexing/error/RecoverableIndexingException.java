package eu.intracherche.intracherche.indexing.error;

public class RecoverableIndexingException extends IndexingException {
    public RecoverableIndexingException(String message) {
        super(message);
    }
    
    public RecoverableIndexingException(String message, Throwable cause) {
        super(message, cause);
    }
}
