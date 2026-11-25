package eu.intracherche.intracherche.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "embedding")
public class EmbeddingConfig {

    private String pythonUrl = "http://localhost:8002";
    private int readTimeoutSeconds = 10;
    private boolean enabled = true;
    private int workers = 1;
    private String embeddingModel = "intfloat/multilingual-e5-base";
    /** Longest a search waits for an embedding slot before falling back to keyword-only search. */
    private long queryMaxWaitMillis = 2000;

    public String getPythonUrl() { return pythonUrl; }
    public void setPythonUrl(String pythonUrl) { this.pythonUrl = pythonUrl; }

    public int getReadTimeoutSeconds() { return readTimeoutSeconds; }
    public void setReadTimeoutSeconds(int readTimeoutSeconds) { this.readTimeoutSeconds = readTimeoutSeconds; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public int getWorkers() { return workers; }
    public void setWorkers(int workers) { this.workers = workers; }

    public long getQueryMaxWaitMillis() { return queryMaxWaitMillis; }
    public void setQueryMaxWaitMillis(long queryMaxWaitMillis) { this.queryMaxWaitMillis = queryMaxWaitMillis; }

    public String getEmbeddingModel() { return embeddingModel; }
    public void setEmbeddingModel(String embeddingModel) { this.embeddingModel = embeddingModel; }
}
