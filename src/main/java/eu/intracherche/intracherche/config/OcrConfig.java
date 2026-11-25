package eu.intracherche.intracherche.config;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for the OCR service.
 */
@Component
@ConfigurationProperties(prefix = "ocr")
public class OcrConfig {

    /**
     * The base URL of the Python OCR service.
     */
    @NotNull
    private String pythonUrl;

    /**
     * Read timeout in seconds for HTTP requests to the OCR service.
     */
    private int readTimeoutSeconds = 120;

    /**
     * Maximum time in minutes to wait for all in-flight OCR documents to complete
     * before the indexing cycle moves on. Raise this if large OCR batches are expected.
     */
    private int maxOcrWaitMinutes = 60;

    private int workers = 2;

    /**
     * The OCR service of a GPU node (port 8004), or empty without one. Pages go
     * there first; {@link #pythonUrl}, the service on this server, reads them while it does not
     * answer.
     */
    private String gpuUrl = "";

    /**
     * Pages sent to a GPU node at once. The node reads one page at a time; the others wait there,
     * so the GPU starts the next page as soon as it finishes one, instead of idling while this
     * server embeds the text it just returned.
     */
    static final int NODE_PAGES_IN_FLIGHT = 4;

    public String getPythonUrl() {
        return pythonUrl;
    }

    public void setPythonUrl(String pythonUrl) {
        this.pythonUrl = pythonUrl;
    }

    public int getReadTimeoutSeconds() {
        return readTimeoutSeconds;
    }

    public void setReadTimeoutSeconds(int readTimeoutSeconds) {
        this.readTimeoutSeconds = readTimeoutSeconds;
    }

    public int getMaxOcrWaitMinutes() {
        return maxOcrWaitMinutes;
    }

    public void setMaxOcrWaitMinutes(int maxOcrWaitMinutes) {
        this.maxOcrWaitMinutes = maxOcrWaitMinutes;
    }

    /**
     * OCR worker processes of the service on this server. On a GPU node it is only the fallback
     * for when the node does not answer, and the allocator gives it a single worker.
     */
    public int getWorkers() { return workers; }
    public void setWorkers(int workers) { this.workers = workers; }

    public String getGpuUrl() { return gpuUrl; }
    public void setGpuUrl(String gpuUrl) { this.gpuUrl = gpuUrl == null ? "" : gpuUrl.trim(); }

    /** Whether pages go to a GPU node first, with this server's OCR service as the fallback. */
    public boolean usesNode() {
        return !gpuUrl.isBlank();
    }

    /** How many pages are read at once: one per local worker, or {@link #NODE_PAGES_IN_FLIGHT}. */
    public int pagesInFlight() {
        return usesNode() ? Math.max(workers, NODE_PAGES_IN_FLIGHT) : workers;
    }
}