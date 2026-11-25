package eu.intracherche.intracherche.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "llm")
public class LlmConfig {

    private String ollamaUrl = "http://localhost:11434";
    private String model = "qwen3.5:9b";
    /**
     * The Ollama of a GPU node, or empty without one. Questions are answered there first, with
     * {@link #gpuModel}; {@link #ollamaUrl} answers them with {@link #model} while it does not.
     */
    private String gpuUrl = "";
    /**
     * The model on the GPU node; empty: {@link #model}. A card runs a larger model in seconds
     * than a processor runs a small one in minutes, so the two usually differ.
     */
    private String gpuModel = "";
    /**
     * Time one Ollama request (query rewriting or answer synthesis) may take. 1800: measured on a
     * processor, a 9B answer took 6 minutes on 8 vCPUs of a Ryzen 5 3600 and 23 minutes on 4 vCPUs of a
     * laptop; a card takes seconds and never comes near the limit.
     */
    private int readTimeoutSeconds = 1800;
    /**
     * Ollama context window (num_ctx) in tokens.
     *
     * <p>Pinned, because leaving it unset does not do what it looks like. Ollama never adopts the
     * model's trained context window as the runtime default: it applies {@code OLLAMA_CONTEXT_LENGTH}
     * — 4096 in Ollama 0.24, 2048 in older builds — to every model alike. qwen3.5:4b and :9b are
     * both trained for 262144 tokens and both get clamped to the same 4096. So an unset value means
     * "whatever the operator's Ollama environment happens to be", which is not a default we can
     * reason about.
     *
     * <p>4096 is not enough. A first question runs about 3k tokens; a follow-up carries the parent
     * question and its full answer and lands near 4.7k. Ollama truncates oldest-first, so the
     * overflow eats the {@code <system_role>} and the leading documents — exactly the instructions
     * that produce citations. It fails silently: no error, no log line, just a worse answer. 8192
     * leaves roughly 1.7x headroom on that worst case.
     *
     * <p>Raise it only after measuring: this model's metadata does not expose {@code head_count_kv},
     * so the KV-cache cost per token is unknown within an order of magnitude (compare {@code ollama ps}
     * size before and after). Because Sherlock answers one question at a time there is only ever one
     * KV cache loaded, so that cost is a single fixed allocation, not a per-session one. num_ctx is a
     * load-time parameter: a value differing from other Ollama callers forces a model reload (slow on
     * CPU). Set 0 to send nothing and defer to the operator's Ollama environment deliberately.
     */
    private int numCtx = 8192;
    /**
     * Threads Ollama may use for the model when it runs on this server's processor (num_thread);
     * 0 = automatic, see {@link #resolvedNumThread()}.
     *
     * <p>Measured on a Ryzen 5 3600 (12 threads) giving a VM 8 vCPUs: generation ran at about 1 token
     * a second with all 8 threads and about 4 with 6, because the VM's own services, the host and the
     * model's threads wait for each other when no vCPU is free. Like num_ctx, it is a load-time
     * parameter: the same value is sent on every call so the model is not reloaded between them.
     */
    private int numThread = 0;
    /** Cores left to Java, Solr and the host's own work, as in ResourceValidator. */
    public static final int RESERVED_CORES = 2;
    /** Below this many cores there is no measured basis to take any away: Ollama chooses. */
    public static final int AUTO_THREADS_MIN_CORES = 6;
    /** Maximum number of Solr snippets passed as context to the LLM. Lower = faster, less context. */
    private int maxSnippets = 5;
    /**
     * Highlight fragment size in characters: the matched extract the evaluation report shows.
     * It does not size the prompt — the LLM reads {@link #maxPassageChars} per passage.
     */
    private int snippetFragSize = 600;
    /**
     * Characters of page text handed to the LLM per passage.
     *
     * <p>A passage reaches the model as one contiguous run of its page, not as the spliced
     * highlight fragments the reranker scores: a fragment cut around a matched term usually
     * begins after the sentence naming whose facts follow, and the model binds the leftover
     * "il" to whoever the question named. A page short enough to fit is sent whole; a longer
     * one is cut to a single window around the match.
     *
     * <p>Budget, against {@link #numCtx}: {@code maxSnippets} x this, in characters, is the
     * bulk of the prompt. 5 x 2500 is about 4.2k tokens of French, which leaves room for the
     * instructions (~0.6k) and, on a follow-up, the parent question and its full answer
     * (~1.5k) inside 8192. Raising this without raising num_ctx makes Ollama truncate
     * oldest-first, silently, starting with the instructions that produce the citations.
     */
    private int maxPassageChars = 2500;
    private boolean enabled = true;
    /** Maximum number of questions that can wait in the in-memory queue before new ones are rejected. */
    private int queueCapacity = 50;
    /**
     * SID of the Samba/AD group whose members are allowed to use Sherlock.
     * Leave empty to allow all authenticated users.
     * Example: S-1-5-21-123456789-987654321-111111111-1234
     */
    private String sherlockGroupSid = "";

    public String getOllamaUrl() { return ollamaUrl; }
    public void setOllamaUrl(String ollamaUrl) { this.ollamaUrl = ollamaUrl; }

    /** True when Ollama runs on another machine: a GPU node, the only reason to put it there. */
    public boolean ollamaElsewhere() {
        return !Hosts.isThisMachine(ollamaUrl);
    }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public String getGpuUrl() { return gpuUrl; }
    public void setGpuUrl(String gpuUrl) { this.gpuUrl = gpuUrl == null ? "" : gpuUrl.trim(); }

    /** Whether questions go to a GPU node first, with {@link #ollamaUrl} as the fallback. */
    public boolean usesNode() { return !gpuUrl.isBlank(); }

    public String getGpuModel() { return gpuModel; }
    public void setGpuModel(String gpuModel) { this.gpuModel = gpuModel == null ? "" : gpuModel.trim(); }

    /** The model the GPU node answers with: llm.gpuModel, or llm.model when it is empty. */
    public String resolvedGpuModel() { return gpuModel.isBlank() ? model : gpuModel; }

    public int getReadTimeoutSeconds() { return readTimeoutSeconds; }
    public void setReadTimeoutSeconds(int readTimeoutSeconds) { this.readTimeoutSeconds = readTimeoutSeconds; }

    public int getNumCtx() { return numCtx; }
    public void setNumCtx(int numCtx) { this.numCtx = numCtx; }

    public int getNumThread() { return numThread; }
    public void setNumThread(int numThread) { this.numThread = Math.max(0, numThread); }

    /**
     * The num_thread to send to this server's Ollama, or 0 to send none and let Ollama choose.
     * Written: that value. Automatic: the cores minus {@link #RESERVED_CORES}, from
     * {@link #AUTO_THREADS_MIN_CORES} cores up. A VM cannot tell its vCPUs from physical cores, so
     * this counts what the operating system reports.
     */
    public int resolvedNumThread() {
        return resolveNumThread(numThread, Runtime.getRuntime().availableProcessors());
    }

    static int resolveNumThread(int configured, int cores) {
        if (configured > 0) return configured;
        return cores >= AUTO_THREADS_MIN_CORES ? cores - RESERVED_CORES : 0;
    }

    public int getMaxSnippets() { return maxSnippets; }
    public void setMaxSnippets(int maxSnippets) { this.maxSnippets = maxSnippets; }

    public int getSnippetFragSize() { return snippetFragSize; }
    public void setSnippetFragSize(int snippetFragSize) { this.snippetFragSize = snippetFragSize; }

    public int getMaxPassageChars() { return maxPassageChars; }
    public void setMaxPassageChars(int maxPassageChars) { this.maxPassageChars = maxPassageChars; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public int getQueueCapacity() { return queueCapacity; }
    public void setQueueCapacity(int queueCapacity) { this.queueCapacity = queueCapacity; }

    public String getSherlockGroupSid() { return sherlockGroupSid; }
    public void setSherlockGroupSid(String sherlockGroupSid) { this.sherlockGroupSid = sherlockGroupSid; }

    private Quota quota = new Quota();

    public Quota getQuota() { return quota; }
    public void setQuota(Quota quota) { this.quota = quota == null ? new Quota() : quota; }

    /** Questions per user and per day, by tier; see SherlockQuota. */
    public enum Tier { BASE, PREMIUM, EXTENDED }

    /**
     * How many questions each user may ask Sherlock per day, in three tiers. Everyone who may use
     * Sherlock is in the base tier; the members of an AD group are raised to premium or extended,
     * the highest tier winning. 0 = unlimited, the default, so an upgrade changes nothing until an
     * admin sets a number.
     */
    public static class Quota {
        private int base = 0;
        private int premium = 0;
        private int extended = 0;
        private String premiumGroupSid = "";
        private String extendedGroupSid = "";
        /** Questions one user may have waiting or being answered at once; 0 = no limit. */
        private int maxPending = 2;

        public int getBase() { return base; }
        public void setBase(int base) { this.base = base; }
        public int getPremium() { return premium; }
        public void setPremium(int premium) { this.premium = premium; }
        public int getExtended() { return extended; }
        public void setExtended(int extended) { this.extended = extended; }
        public String getPremiumGroupSid() { return premiumGroupSid; }
        public void setPremiumGroupSid(String sid) { this.premiumGroupSid = sid == null ? "" : sid.trim(); }
        public String getExtendedGroupSid() { return extendedGroupSid; }
        public void setExtendedGroupSid(String sid) { this.extendedGroupSid = sid == null ? "" : sid.trim(); }
        public int getMaxPending() { return maxPending; }
        public void setMaxPending(int maxPending) { this.maxPending = maxPending; }

        /** The highest tier the SIDs reach. */
        public Tier tierOf(java.util.Collection<String> sids) {
            if (!extendedGroupSid.isBlank() && sids.contains(extendedGroupSid)) return Tier.EXTENDED;
            if (!premiumGroupSid.isBlank() && sids.contains(premiumGroupSid)) return Tier.PREMIUM;
            return Tier.BASE;
        }

        /** Questions per day in a tier; 0 = unlimited. */
        public int limitOf(Tier tier) {
            return switch (tier) {
                case BASE -> base;
                case PREMIUM -> premium;
                case EXTENDED -> extended;
            };
        }
    }

    /**
     * True when the user may use Sherlock. Single source of truth: SherlockController gates on it,
     * and /api/auth/me reports it so the frontend only renders the pane for users who can use it.
     * Note the deliberate contrast with {@link AdminConfig#isAdmin}: a blank SID here opens Sherlock
     * to every authenticated user, whereas a blank admin SID locks the panel for everyone.
     * {@code enabled: false} closes Sherlock to everyone, group members included.
     */
    public boolean canUseSherlock(Authentication auth) {
        if (!enabled) return false;
        if (sherlockGroupSid == null || sherlockGroupSid.isBlank()) return true;
        if (auth == null || auth.getAuthorities() == null) return false;
        return auth.getAuthorities().stream().anyMatch(a -> sherlockGroupSid.equals(a.getAuthority()));
    }
}
