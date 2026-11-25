package eu.intracherche.intracherche.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "reranker")
public class RerankerConfig {

    /** Candidates when left at 0, on a processor and on a GPU. */
    static final int CPU_CANDIDATES = 40, GPU_CANDIDATES = 100;

    private String pythonUrl = "http://localhost:8003";
    /**
     * The reranker of a GPU node, or empty without one. Questions are scored there first;
     * {@link #pythonUrl} scores them while it does not answer.
     */
    private String gpuUrl = "";
    private int readTimeoutSeconds = 120;
    private boolean enabled = true;
    /**
     * How many Solr candidates to retrieve before reranking. Must be > llm.maxSnippets.
     *
     * <p>The cross-encoder never sees a passage outside this pool, so this is where recall is
     * decided. 20 was too few even on the demo corpus: the page naming the suspect of an
     * investigation report ranks 30th in retrieval, so at 20 the model was handed five pages of
     * the right report without the right page, and filled the gap with the wrong person.
     *
     * <p>0 = auto: 40 on a processor, 100 on a GPU, which scores 100 in about a second. Which
     * one is read from the running reranker (its /health reports the device), not declared here.
     */
    private int candidates = 0;
    private int workers = 1;
    /** Torch threads per reranker worker process. 0 = auto (half the machine's cores). */
    private int threads = 0;
    /**
     * Each passage — the page window the LLM will read — is cut to this many characters before
     * scoring. 1800 is about what the model reads anyway (512 tokens, question included), and
     * reaches the answer on pages where it sits past the first 800 characters. Scoring time grows
     * with it: 40 passages took ~88 s against ~64 s at 800 on a CPU-only laptop.
     */
    private int maxPassageChars = 1800;
    /**
     * Passages per forward pass. 8 suits a CPU, where the work is done by threads and a bigger
     * batch only grows the memory spike. A GPU is starved at 8. 0 = auto: the reranker picks
     * 8 on a processor, 32 on a GPU.
     */
    private int batchSize = 0;
    private String rerankerModel = "BAAI/bge-reranker-v2-m3";

    /** Sherlock on until Spring hands over the real setting; tests that build this by hand get that. */
    private LlmConfig llm = new LlmConfig();

    @Autowired
    public void useLlm(LlmConfig llm) { this.llm = llm; }

    public String getPythonUrl() { return pythonUrl; }
    public void setPythonUrl(String pythonUrl) { this.pythonUrl = pythonUrl; }

    public String getGpuUrl() { return gpuUrl; }
    public void setGpuUrl(String gpuUrl) { this.gpuUrl = gpuUrl == null ? "" : gpuUrl.trim(); }

    /** Whether questions go to a GPU node first, with {@link #pythonUrl} as the fallback. */
    public boolean usesNode() { return !gpuUrl.isBlank(); }

    public int getReadTimeoutSeconds() { return readTimeoutSeconds; }
    public void setReadTimeoutSeconds(int s) { this.readTimeoutSeconds = s; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    /**
     * Whether Sherlock reranks at all. The reranker serves Sherlock alone, so llm.enabled: false
     * turns it off as well: left on, it was started, given a share of the RAM budget taken from
     * the indexing services, and health-checked, for questions nobody can ask.
     */
    public boolean isUsed() { return enabled && llm.isEnabled(); }

    /** Whether IntraCherche starts and allocates a reranker on this server. */
    public boolean startsHere() { return isUsed() && runsHere(); }

    public int getCandidates() { return candidates; }
    public void setCandidates(int candidates) { this.candidates = candidates; }

    /** The configured count, or, when set to 0, the default for the device the reranker runs on. */
    public int resolvedCandidates(boolean onGpu) {
        if (candidates > 0) return candidates;
        return onGpu ? GPU_CANDIDATES : CPU_CANDIDATES;
    }

    /**
     * The most passages a reranker started here accepts. It is started before its device is
     * known, so with the auto count the cap is the GPU's: a cap below what Java sends would make
     * the reranker refuse every question.
     */
    public int maxPassagesHere() {
        return candidates > 0 ? candidates : GPU_CANDIDATES;
    }

    /**
     * Whether IntraCherche starts the reranker itself: only when pythonUrl names this machine.
     * A reranker on a GPU node is that node's to run, and a local copy would load a model for
     * nothing on the port the URL names.
     */
    public boolean runsHere() {
        return Hosts.isThisMachine(pythonUrl);
    }

    public int getWorkers() { return workers; }
    public void setWorkers(int workers) { this.workers = workers; }

    public int getThreads() { return threads; }
    public void setThreads(int threads) { this.threads = threads; }

    /** Resolved thread count: the configured value, or half the cores when set to 0. */
    public int resolvedThreads() {
        return threads > 0 ? threads : Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
    }

    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int batchSize) { this.batchSize = batchSize; }

    public int getMaxPassageChars() { return maxPassageChars; }
    public void setMaxPassageChars(int maxPassageChars) { this.maxPassageChars = maxPassageChars; }

    public String getRerankerModel() { return rerankerModel; }
    public void setRerankerModel(String rerankerModel) { this.rerankerModel = rerankerModel; }
}
