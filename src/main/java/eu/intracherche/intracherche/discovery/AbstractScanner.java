package eu.intracherche.intracherche.discovery;

import eu.intracherche.intracherche.config.IndexedDirConfig;
import eu.intracherche.intracherche.queues.DiscoveryQueue;

public abstract class AbstractScanner {
    protected final DiscoveryQueue discoveryQueue;

    protected AbstractScanner(DiscoveryQueue discoveryQueue) {
        this.discoveryQueue = discoveryQueue;
    }

    public abstract void scan(IndexedDirConfig config) throws Exception;
}
