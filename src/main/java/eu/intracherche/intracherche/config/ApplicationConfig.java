package eu.intracherche.intracherche.config;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * THis class maps the raw strings and values from  application.yml into a Java object.
 */
@Component
@ConfigurationProperties(prefix = "") // Empty prefix because your YAML starts at the root
public class ApplicationConfig {

    @NotNull
    private RegistryConfig registryConfig;

    @NotNull
    private IndexingConfig indexingConfig;

    private QueueConfig queueConfig;

    private SolrConfig solrConfig;


    private IndexesConfig indexesConfig;
    
    private OcrConfig ocrConfig;

    public RegistryConfig getRegistryConfig() { return registryConfig; }
    public void setRegistryConfig(RegistryConfig registryConfig) { this.registryConfig = registryConfig; }

    /**
     * Returns the indexing configuration.
     *
     * @return the indexing configuration
     */
    public IndexingConfig getIndexingConfig() { return indexingConfig; }
    public void setIndexingConfig(IndexingConfig indexingConfig) { this.indexingConfig = indexingConfig; }

    public QueueConfig getQueueConfig() { return queueConfig; }
    public void setQueueConfig(QueueConfig queueConfig) { this.queueConfig = queueConfig; }

    public SolrConfig getSolrConfig() { return solrConfig; }
    public void setSolrConfig(SolrConfig solrConfig) { this.solrConfig = solrConfig; }
    
    public OcrConfig getOcrConfig() { return ocrConfig; }
    public void setOcrConfig(OcrConfig ocrConfig) { this.ocrConfig = ocrConfig; }


    /**
     * Returns the configuration for indexes.
     *
     * @return the indexes configuration
     */
    public IndexesConfig getIndexesConfig() { return indexesConfig;  }
    public void setIndexesConfig(IndexesConfig indexesConfig) {this.indexesConfig = indexesConfig; }
}

