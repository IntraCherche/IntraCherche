package eu.intracherche.intracherche.process;

import eu.intracherche.intracherche.config.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Validates that the configured Python service worker counts fit in available RAM,
 * and resolves auto-mode (workers=0) to a concrete count before services are launched.
 *
 * Called explicitly by PythonServiceManager before any service is started.
 * Mutates each config's workers field in-place so the launcher reads the final value.
 */
@Component
public class ResourceValidator {

    private static final Logger log = LoggerFactory.getLogger(ResourceValidator.class);

    private static final int JAVA_SOLR_RESERVED_CORES = 2;

    /** Solr's own heap when solr.in.sh sets none: the bundled default. */
    static final long DEFAULT_SOLR_HEAP_MB = 512;

    /** Where the installer (or an admin) sets Solr's heap; relative to the install directory, like SolrProcessManager's. */
    private static final Path SOLR_IN_SH = Path.of("solr", "bin", "solr.in.sh");

    private static final Pattern SOLR_HEAP_LINE = Pattern.compile("^\\s*SOLR_HEAP=[\"']?(\\d+)([mMgG])?[\"']?\\s*$");
    private static final Pattern SOLR_JAVA_MEM_LINE = Pattern.compile("^\\s*SOLR_JAVA_MEM=.*-Xmx(\\d+)([mMgG])?.*$");

    /** Left to the OS: page cache over Solr's index files, and room for everything small. */
    private static final long OS_FLOOR_MB = 1024;

    /**
     * GB of weights per billion parameters at the quantisation Ollama ships (Q4_K_M).
     * Measured against the 9B model IntraCherche delivers: 6.6 GB resident, 0.73 GB per
     * billion. Rounded up, because under-reserving is what this constant exists to stop.
     */
    private static final double LLM_GB_PER_BILLION_PARAMS = 0.75;

    /** KV cache, context and the runtime around the weights. */
    private static final long LLM_RUNTIME_OVERHEAD_MB = 1024;

    /** When the model's tag says nothing about its size, assume the one we ship. */
    private static final long UNKNOWN_LLM_RAM_MB = 7 * 1024;

    public record ServiceAllocation(String key, String name, int workers, int ramMb, boolean auto) {}
    public record AllocationResult(int totalCores, int reservedCores,
                                   long totalRamMb, long budgetRamMb,
                                   long reservedRamMb, long heapRamMb, long llmRamMb,
                                   List<ServiceAllocation> services) {}

    private volatile AllocationResult lastAllocation;

    public AllocationResult getLastAllocation() { return lastAllocation; }

    // Approximate RAM per worker in MB, keyed by model name (or "ocr" for the OCR service).
    // These are conservative estimates including model weights + ONNX/PyTorch runtime overhead.
    // OCR is a peak, not a resting size: a worker idles at ~150 MB but reaches ~1.3 GB while
    // detecting text on a 2500-px page (measured over 22 scanned pages, RapidOCR 3.9).
    private static final Map<String, Integer> MODEL_RAM_MB = Map.of(
        "ocr",                             1300,
        "fr_core_news_md",                  300,
        "fr_core_news_lg",                  600,
        "intfloat/multilingual-e5-base",   1100,
        "intfloat/multilingual-e5-small",   470,
        "BAAI/bge-reranker-v2-m3",         1100
    );
    private static final int UNKNOWN_MODEL_RAM_MB = 1024;

    private final ResourceConfig    resourceConfig;
    private final OcrConfig         ocrConfig;
    private final NerConfig         nerConfig;
    private final EmbeddingConfig   embeddingConfig;
    private final RerankerConfig    rerankerConfig;
    private final LlmConfig         llmConfig;

    public ResourceValidator(ResourceConfig resourceConfig,
                             OcrConfig ocrConfig, NerConfig nerConfig,
                             EmbeddingConfig embeddingConfig, RerankerConfig rerankerConfig,
                             LlmConfig llmConfig) {
        this.resourceConfig  = resourceConfig;
        this.ocrConfig       = ocrConfig;
        this.nerConfig       = nerConfig;
        this.embeddingConfig = embeddingConfig;
        this.rerankerConfig  = rerankerConfig;
        this.llmConfig       = llmConfig;
    }

    /**
     * Main entry point. Resolves auto workers, checks RAM, and writes back the resolved
     * worker count to each config. Throws {@link InsufficientRamException} if explicit
     * worker counts cannot fit in available RAM.
     */
    public void validateAndAllocate() {
        int  cores      = Runtime.getRuntime().availableProcessors();
        long totalRamMb = getTotalRamMb();
        long reservedMb = reservedMb();
        long budgetMb   = totalRamMb - reservedMb;

        List<ServiceSpec> specs = buildSpecs(cores);

        for (ServiceSpec s : specs) {
            if (!MODEL_RAM_MB.containsKey(s.modelKey)) {
                log.warn("[Resources] Unknown model '{}' for {} — assuming {} MB RAM per worker",
                    s.modelKey, s.name, UNKNOWN_MODEL_RAM_MB);
            }
        }

        long neededMb = totalNeeded(specs);

        if (neededMb > budgetMb) {
            List<ServiceSpec> autoSpecs   = specs.stream().filter(s -> s.isAuto).toList();
            long              explicitMb  = specs.stream().filter(s -> !s.isAuto)
                                                 .mapToLong(ServiceSpec::neededMb).sum();

            if (autoSpecs.isEmpty() || explicitMb >= budgetMb) {
                    throw new InsufficientRamException(buildFailureMessage(specs, neededMb, budgetMb, totalRamMb, reservedMb));
            }

            // Scale down auto services to fit within their share of the budget
            scaleDown(autoSpecs, budgetMb - explicitMb);
            neededMb = totalNeeded(specs);

            if (neededMb > budgetMb) {
                log.warn("[Resources] RAM tight even with minimum 1 worker per auto service "
                    + "({} MB needed, {} MB available). Consider increasing resources.reservedRamGb or adding RAM.",
                    neededMb, budgetMb);
            }
        }

        // Write resolved workers back so PythonServiceManager reads the final values
        for (ServiceSpec spec : specs) {
            switch (spec.configKey) {
                case "ocr"       -> ocrConfig.setWorkers(spec.workers);
                case "ner"       -> nerConfig.setWorkers(spec.workers);
                case "embedding" -> embeddingConfig.setWorkers(spec.workers);
                case "reranker"  -> rerankerConfig.setWorkers(spec.workers);
                default          -> throw new IllegalStateException("Unknown service " + spec.configKey);
            }
        }

        this.lastAllocation = new AllocationResult(
            cores, JAVA_SOLR_RESERVED_CORES, totalRamMb, budgetMb, reservedMb, maxHeapMb(), llmRamMb(),
            specs.stream()
                 .map(s -> new ServiceAllocation(s.configKey, s.name, s.workers, s.ramMb, s.isAuto))
                 .toList()
        );

        logSummary(specs, neededMb, budgetMb, totalRamMb, reservedMb);
    }


    /** The memory budget for a given choice of heaps, for the admin panel's Memory tab. */
    public record Preview(long totalRamMb, long heapMb, long solrMb, long osMb, long llmMb,
                          long reservedMb, long budgetMb, long neededMb, long minimumMb,
                          boolean servicesKnown, List<ServiceAllocation> services) {}

    /**
     * What the budget would be if the Java heap, Solr's heap and the reservation floor were these.
     * The services are those allocated at this start (their workers already scaled to fit), so
     * the answer to "would this still start?" is the same arithmetic as {@link #validateAndAllocate}.
     */
    public Preview preview(long heapMb, long solrMb, int reservedGb) {
        long total = getTotalRamMb();
        long llm = llmRamMb();
        long reserved = Math.max((long) reservedGb * 1024, heapMb + solrMb + OS_FLOOR_MB + llm);
        AllocationResult alloc = lastAllocation;
        List<ServiceAllocation> services = alloc == null ? List.of() : alloc.services();
        long needed = services.stream().mapToLong(x -> (long) x.workers() * x.ramMb()).sum();
        long minimum = services.stream().mapToLong(ServiceAllocation::ramMb).sum();
        return new Preview(total, heapMb, solrMb, OS_FLOOR_MB, llm, reserved, total - reserved, needed, minimum,
                alloc != null, services);
    }

    /** Solr's heap as solr.in.sh sets it now. */
    public long currentSolrHeapMb() { return solrHeapMb(); }

    /** This JVM's heap ceiling. */
    public long currentHeapMb() { return maxHeapMb(); }

    /** The server's RAM. */
    public long totalRamMb() { return getTotalRamMb(); }

    // ── helpers ─────────────────────────────────────────────────────────────

    private List<ServiceSpec> buildSpecs(int cores) {
        // Reserve JAVA_SOLR_RESERVED_CORES for Java (PDFBox, virtual threads) and Solr.
        // Distribute the remaining cores across the 4 services; give the integer-division
        // remainder to the highest-throughput bottlenecks: embedding first (always the
        // slowest for text), then OCR (bottleneck for scanned), then NER.
        int pythonCores   = cores - JAVA_SOLR_RESERVED_CORES;
        int base          = pythonCores / 4;
        int extra         = pythonCores % 4;
        int embeddingAuto = Math.max(1, base + (extra > 0 ? 1 : 0));
        // With a GPU node, this server's OCR only reads pages while the node does not answer:
        // one worker keeps indexing going then, and leaves the RAM to the other services.
        int ocrAuto       = ocrConfig.usesNode() ? 1 : Math.max(1, base + (extra > 1 ? 1 : 0));
        int nerAuto       = Math.max(1, base + (extra > 2 ? 1 : 0));
        int rerankerAuto  = Math.max(1, base);
        List<ServiceSpec> specs = new ArrayList<>(List.of(
            new ServiceSpec("ocr",       "OCR",       ocrConfig.getWorkers(),       ocrAuto,       "ocr",                              ramFor("ocr")),
            new ServiceSpec("ner",       "NER",       nerConfig.getWorkers(),        nerAuto,       nerConfig.getNerModel(),            ramFor(nerConfig.getNerModel())),
            new ServiceSpec("embedding", "Embedding", embeddingConfig.getWorkers(),  embeddingAuto, embeddingConfig.getEmbeddingModel(), ramFor(embeddingConfig.getEmbeddingModel()))
        ));
        // A reranker on a GPU node is not started here, nor is one Sherlock does not use, so
        // neither takes any of this server's RAM.
        if (rerankerConfig.startsHere()) {
            specs.add(new ServiceSpec("reranker",  "Reranker",  rerankerConfig.getWorkers(),   rerankerAuto,  rerankerConfig.getRerankerModel(),   ramFor(rerankerConfig.getRerankerModel())));
        }
        return specs;
    }

    /** Iteratively reduces the most RAM-hungry service (still > 1 worker) until it fits. */
    private static void scaleDown(List<ServiceSpec> specs, long budgetMb) {
        while (totalNeeded(specs) > budgetMb) {
            ServiceSpec toReduce = specs.stream()
                .filter(s -> s.workers > 1)
                .max(Comparator.comparingLong(ServiceSpec::neededMb))
                .orElse(null);
            if (toReduce == null) break; // all at minimum 1
            toReduce.workers--;
        }
    }

    private static long totalNeeded(List<ServiceSpec> specs) {
        return specs.stream().mapToLong(ServiceSpec::neededMb).sum();
    }

    private void logSummary(List<ServiceSpec> specs, long neededMb, long budgetMb, long totalRamMb, long reservedMb) {
        log.info("=== IntraCherche Resource Allocation ===");
        log.info("  System   : {} GB RAM,  {} logical cores", gbStr(totalRamMb), Runtime.getRuntime().availableProcessors());
        long llmMb = llmRamMb();
        log.info("  Budget   : {} GB  ({} GB reserved: {} GB Java heap + {} GB Solr + {} GB OS{})",
            gbStr(budgetMb), gbStr(reservedMb), gbStr(maxHeapMb()), gbStr(solrHeapMb()), gbStr(OS_FLOOR_MB),
            llmMb > 0 ? " + " + gbStr(llmMb) + " GB Sherlock (" + llmConfig.getModel() + ")" : "");
        for (ServiceSpec s : specs) {
            log.info(String.format("  %-10s : %d worker(s)  x  ~%s GB  =  ~%s GB%s",
                s.name, s.workers, gbStr(s.ramMb), gbStr(s.neededMb()),
                s.isAuto ? "  [auto]" : ""));
        }
        log.info("  Total    : ~{} GB allocated  /  {} GB budget", gbStr(neededMb), gbStr(budgetMb));
        log.info("=========================================");
    }

    private String buildFailureMessage(List<ServiceSpec> specs, long neededMb, long budgetMb, long totalRamMb, long reservedMb) {
        var sb = new StringBuilder();
        sb.append("\n\n[STARTUP FAILURE] Insufficient RAM for the configured worker count.\n\n");

        for (ServiceSpec s : specs) {
            sb.append(String.format("  %-10s  x%d  =  %.1f GB%n", s.name, s.workers, s.neededMb() / 1024.0));
        }
        sb.append(String.format("  Reserved    :  %.1f GB  (%.1f GB Java heap + %.1f GB Solr + %.1f GB OS%s)%n",
            reservedMb / 1024.0, maxHeapMb() / 1024.0, solrHeapMb() / 1024.0, OS_FLOOR_MB / 1024.0,
            llmRamMb() > 0 ? String.format(" + %.1f GB Sherlock", llmRamMb() / 1024.0) : ""));
        sb.append(String.format("  Total needed:  %.1f GB    Left for these services: %s  (system: %.1f GB)%n%n",
            neededMb / 1024.0,
            budgetMb > 0 ? String.format("%.1f GB", budgetMb / 1024.0) : "none, the reservation alone exceeds the system's memory",
            totalRamMb / 1024.0));

        // One worker each is the floor: below it, the worker count is not the lever.
        long minimumMb = specs.stream().mapToLong(s -> s.ramMb).sum();
        long minRamGb  = (long) Math.ceil((reservedMb + minimumMb) / 1024.0);
        int  option    = 1;

        if (budgetMb < minimumMb) {
            sb.append(String.format("  Even one worker per service (%.1f GB) does not fit, so reducing workers cannot help.%n%n",
                minimumMb / 1024.0));
            if (llmRamMb() > 0) {
                sb.append(String.format("  Option %d — turn Sherlock off here, which gives back %.1f GB (llm.enabled: false in application.yaml),%n"
                        + "      or run its model on another host (llm.ollamaUrl).%n", option++, llmRamMb() / 1024.0));
            }
            sb.append(String.format("  Option %d — give the Java heap less memory (IC_HEAP_MB in /etc/intracherche/jvm.conf).%n", option++));
        } else {
            int[] suggested   = suggestReducedWorkers(specs, budgetMb);
            long  suggestedMb = 0;
            for (int i = 0; i < specs.size(); i++) suggestedMb += (long) suggested[i] * specs.get(i).ramMb;
            sb.append(String.format("  Option %d — reduce workers in application.yaml (these need %.1f GB):%n", option++, suggestedMb / 1024.0));
            for (int i = 0; i < specs.size(); i++) {
                if (suggested[i] != specs.get(i).workers) {
                    sb.append(String.format("      %s.workers=%d%n", specs.get(i).configKey, suggested[i]));
                }
            }
            if (specs.stream().anyMatch(s -> !s.isAuto)) {
                sb.append(String.format("%n  Option %d — let IntraCherche choose the counts: set to 0 (auto) the ones you fixed:%n", option++));
                for (ServiceSpec s : specs) {
                    if (!s.isAuto) sb.append(String.format("      %s.workers=0%n", s.configKey));
                }
            }
        }
        sb.append(String.format("%n  Option %d — give the machine at least %d GB of RAM (recommended)%n%n", option, minRamGb));
        return sb.toString();
    }

    /** Returns a copy of the workers array with the minimum reductions needed to fit in budgetMb. */
    private static int[] suggestReducedWorkers(List<ServiceSpec> specs, long budgetMb) {
        int[] workers = specs.stream().mapToInt(s -> s.workers).toArray();
        int[] ramMbs  = specs.stream().mapToInt(s -> s.ramMb).toArray();
        long  needed  = 0;
        for (int i = 0; i < workers.length; i++) needed += (long) workers[i] * ramMbs[i];
        while (needed > budgetMb) {
            int  maxIdx = -1;
            long maxRam = 0;
            for (int i = 0; i < workers.length; i++) {
                long ram = (long) workers[i] * ramMbs[i];
                if (workers[i] > 1 && ram > maxRam) { maxRam = ram; maxIdx = i; }
            }
            if (maxIdx < 0) break;
            needed -= ramMbs[maxIdx];
            workers[maxIdx]--;
        }
        return workers;
    }

    /**
     * What the Python model services must not count on. The configured value is a floor
     * an admin can raise; the effective one also has to cover this JVM's own heap, which
     * the installer sizes from the server's RAM (and which a bigger server therefore
     * makes bigger). Without that, the two numbers drift apart on every machine that is
     * not the 16 GB one the 3 GB default was written for, and the services are handed a
     * budget the indexer is already holding.
     */
    private long reservedMb() {
        long configuredMb = (long) resourceConfig.getReservedRamGb() * 1024;
        return Math.max(configuredMb, maxHeapMb() + solrHeapMb() + OS_FLOOR_MB + llmRamMb());
    }

    /**
     * What Sherlock's model holds on this machine. Ollama keeps the weights resident from
     * the first question until its own idle timeout, and it is not one of the services
     * allocated below — it is a separate systemd unit that simply takes what it needs.
     * Unaccounted, that memory comes out of Solr's page cache: measured on a 16 GB server,
     * one question moved 6.6 GB from cache to the model.
     *
     * Nothing is reserved when Sherlock is off, or when Ollama runs on another host — a
     * GPU box's memory is not this server's to set aside.
     */
    private long llmRamMb() {
        if (llmConfig == null || !llmConfig.isEnabled()) return 0;
        if (!isLocalOllama(llmConfig.getOllamaUrl())) return 0;
        return modelRamMb(llmConfig.getModel());
    }

    /**
     * True when Ollama answers on this machine, so its memory is ours to account for.
     *
     * Every answer but a confident "that is another host" is local. Matching "localhost"
     * and 127.* as text would miss a server that names itself — ic-server.corp.local, or
     * its own LAN address — and missing it is the expensive mistake: the reservation goes
     * back to ignoring a model that really is resident, which is the bug this fixes.
     * A name that will not resolve counts as local too, for the same reason: reserving
     * memory that turns out to be elsewhere costs some workers, not reserving memory that
     * is here costs Solr's page cache and, under load, the machine.
     */
    static boolean isLocalOllama(String ollamaUrl) {
        return Hosts.isThisMachine(ollamaUrl);
    }

    /**
     * Weights plus runtime for a model, read from the parameter count in its tag:
     * "qwen3.5:9b" is 9 billion, "llama3.1:70b-instruct-q4_K_M" is 70. A tag that names
     * no size falls back to the size of the model IntraCherche ships. An admin running an
     * unusual quantisation reads the real figure from `ollama ps` and sets
     * resources.reservedRamGb accordingly.
     */
    static long modelRamMb(String modelTag) {
        if (modelTag != null) {
            String tag = modelTag.substring(modelTag.lastIndexOf(':') + 1);
            var matcher = java.util.regex.Pattern
                    .compile("^(\\d+(?:[.,]\\d+)?)b", java.util.regex.Pattern.CASE_INSENSITIVE)
                    .matcher(tag);
            if (matcher.find()) {
                double billions = Double.parseDouble(matcher.group(1).replace(',', '.'));
                return Math.round(billions * LLM_GB_PER_BILLION_PARAMS * 1024) + LLM_RUNTIME_OVERHEAD_MB;
            }
        }
        log.warn("[Resources] Model '{}' does not say its size — reserving {} MB for it", modelTag, UNKNOWN_LLM_RAM_MB);
        return UNKNOWN_LLM_RAM_MB;
    }

    private static long solrHeapMb() {
        return solrHeapMb(SOLR_IN_SH);
    }

    /**
     * The heap Solr is started with: SOLR_HEAP in solr.in.sh (Solr gives it precedence over
     * SOLR_JAVA_MEM), else the -Xmx of SOLR_JAVA_MEM, else Solr's bundled default. The
     * installer sizes it from the server's RAM, so the budget has to read it, not assume it.
     */
    static long solrHeapMb(Path solrInSh) {
        long heap = 0;
        long javaMem = 0;
        try {
            for (String line : Files.readAllLines(solrInSh)) {
                Matcher h = SOLR_HEAP_LINE.matcher(line);
                if (h.matches()) heap = toMb(h.group(1), h.group(2));
                Matcher j = SOLR_JAVA_MEM_LINE.matcher(line);
                if (j.matches()) javaMem = toMb(j.group(1), j.group(2));
            }
        } catch (IOException | RuntimeException e) {
            return DEFAULT_SOLR_HEAP_MB;
        }
        if (heap > 0) return heap;
        return javaMem > 0 ? javaMem : DEFAULT_SOLR_HEAP_MB;
    }

    private static long toMb(String number, String unit) {
        long value = Long.parseLong(number);
        return unit != null && unit.equalsIgnoreCase("g") ? value * 1024 : value;
    }

    /** This JVM's -Xmx, as the installer set it. */
    private static long maxHeapMb() {
        return Runtime.getRuntime().maxMemory() / (1024L * 1024L);
    }

    private static long getTotalRamMb() {
        try {
            var bean = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
            return bean.getTotalMemorySize() / (1024L * 1024L);
        } catch (Exception e) {
            log.warn("[Resources] Could not read system RAM — assuming 8 GB for worker allocation");
            return 8 * 1024L;
        }
    }

    private static int ramFor(String modelKey) {
        return MODEL_RAM_MB.getOrDefault(modelKey, UNKNOWN_MODEL_RAM_MB);
    }

    private static String gbStr(long mb) { return String.format("%.1f", mb / 1024.0); }
    private static String gbStr(int mb)  { return gbStr((long) mb); }

    // ── inner types ──────────────────────────────────────────────────────────

    private static class ServiceSpec {
        final String configKey;
        final String name;
        final String modelKey;
        final int    ramMb;
        final boolean isAuto;
        int workers;

        ServiceSpec(String configKey, String name, int configured, int autoWorkers, String modelKey, int ramMb) {
            this.configKey = configKey;
            this.name      = name;
            this.modelKey  = modelKey;
            this.ramMb     = ramMb;
            this.isAuto    = (configured == 0);
            this.workers   = isAuto ? autoWorkers : configured;
        }

        long neededMb() { return (long) workers * ramMb; }
    }

    public static class InsufficientRamException extends RuntimeException {
        public InsufficientRamException(String message) { super(message); }
    }
}
