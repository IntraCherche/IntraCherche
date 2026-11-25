package eu.intracherche.intracherche.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "resources")
public class ResourceConfig {

    /** RAM in GB reserved for Java heap, Solr, and the OS — not available for Python services. */
    private int reservedRamGb = 3;

    /**
     * Linux scheduling niceness (0-19) for the Python services that indexing keeps busy: OCR,
     * NER and embedding. When CPU is short, the web application, Solr and the reranker get it
     * first, so searches stay fast while a large batch is indexed. 0 disables it.
     */
    private int indexingServicesNice = 10;

    public int getReservedRamGb() { return reservedRamGb; }
    public void setReservedRamGb(int reservedRamGb) { this.reservedRamGb = reservedRamGb; }

    public int getIndexingServicesNice() { return indexingServicesNice; }
    public void setIndexingServicesNice(int indexingServicesNice) { this.indexingServicesNice = indexingServicesNice; }
}
