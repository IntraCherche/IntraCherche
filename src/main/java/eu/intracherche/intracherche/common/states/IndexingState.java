package eu.intracherche.intracherche.common.states;

/**
 * Represents the indexing state of a file in the registry.
 */
public enum IndexingState {
    /**
     * File has been discovered on the filesystem but not yet scheduled for indexing.
     */
    DISCOVERED,
    
    /**
     * File has been scheduled for indexing.
     */
    QUEUED,
    
    /**
     * File is currently being processed for indexing.
     */
    INDEXING,
    
    /**
     * File has been successfully indexed (Solr ACK received).
     */
    INDEXED,
    
    /**
     * File indexing failed during the last attempt.
     */
    FAILED
}
