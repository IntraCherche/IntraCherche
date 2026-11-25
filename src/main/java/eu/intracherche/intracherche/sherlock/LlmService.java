package eu.intracherche.intracherche.sherlock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.intracherche.intracherche.config.LlmConfig;
import eu.intracherche.intracherche.services.NodeFallback;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

@Service
public class LlmService {

    private static final Logger log = LoggerFactory.getLogger(LlmService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** The one sentence that stands for "the corpus does not answer this". */
    static final String NOT_FOUND = "Je ne trouve pas cette information dans les documents fournis.";

    private final LlmConfig config;
    /** The GPU node's Ollama (llm.gpuUrl), tried first; null without one. */
    private final NodeFallback fallback;
    /** Runs each Ollama call on its own thread so a timeout can tear it down out-of-band. */
    private final ExecutorService ioExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "llm-io");
        t.setDaemon(true);
        return t;
    });

    @Autowired
    public LlmService(LlmConfig config) {
        this(config, Clock.systemUTC());
    }

    LlmService(LlmConfig config, Clock clock) {
        this.config = config;
        this.fallback = config.usesNode()
                ? new NodeFallback("Sherlock model", trimSlash(config.getGpuUrl()), clock) : null;
    }

    @PreDestroy
    public void shutdown() {
        ioExecutor.shutdownNow();
    }

    @PostConstruct
    public void logAvailability() {
        if (!config.isEnabled()) {
            log.info("LLM service is disabled (llm.enabled=false)");
            return;
        }
        if (fallback != null) {
            if (isNodeHealthy()) {
                log.info("Sherlock answers on the GPU node's Ollama at {} (model: {}), and at {} (model: {}) "
                        + "while it does not answer", fallback.nodeUrl(), config.resolvedGpuModel(),
                        config.getOllamaUrl(), config.getModel());
            } else {
                log.warn("The GPU node's Ollama does not answer at {}: Sherlock answers at {} (model: {}) until it does",
                        fallback.nodeUrl(), config.getOllamaUrl(), config.getModel());
            }
        }
        if (isLocalHealthy()) {
            log.info("Ollama LLM service reachable at {} (model: {})", config.getOllamaUrl(), config.getModel());
        } else {
            log.warn("========================================================");
            log.warn("Ollama LLM service is NOT reachable at {}", config.getOllamaUrl());
            log.warn("Sherlock questions will fail until it is started.");
            log.warn("Install: curl -fsSL https://ollama.com/install.sh | sh");
            log.warn("Pull model: ollama pull {}", config.getModel());
            log.warn("========================================================");
        }
    }

    /** Whether some Ollama can answer a question: the GPU node's, or the one at llm.ollamaUrl. */
    public boolean isHealthy() {
        return isNodeHealthy() || isLocalHealthy();
    }

    /** Whether the Ollama at llm.ollamaUrl answers. */
    public boolean isLocalHealthy() {
        return answers(baseUrl());
    }

    /** Whether the GPU node's Ollama answers; false without one. */
    public boolean isNodeHealthy() {
        return fallback != null && answers(fallback.nodeUrl());
    }

    private static boolean answers(String baseUrl) {
        try {
            HttpClient probe = HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/tags"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<Void> resp = probe.send(req, HttpResponse.BodyHandlers.discarding());
            return resp.statusCode() >= 200 && resp.statusCode() < 300;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Where Ollama holds Sherlock's model right now, read from {@code /api/ps}; null when Ollama
     * cannot be read. Ollama unloads a model after a few idle minutes and places it again on the
     * next question, so "not loaded" says nothing about where it will go.
     */
    public ModelPlacement placement() {
        return placement(baseUrl(), config.getModel());
    }

    /** Where the GPU node's Ollama holds its model, as {@link #placement()}; null without a node. */
    public ModelPlacement nodePlacement() {
        return fallback == null ? null : placement(fallback.nodeUrl(), config.resolvedGpuModel());
    }

    private static ModelPlacement placement(String baseUrl, String model) {
        try {
            HttpClient probe = HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/ps"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<String> resp = probe.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) return null;
            return ModelPlacement.of(MAPPER.readTree(resp.body()), model);
        } catch (Exception e) {
            log.debug("Could not read where Ollama holds the model: {}", e.getMessage());
            return null;
        }
    }

    /**
     * How much of a model sits in GPU memory. Ollama places a model when it loads it and keeps it
     * there: loaded while another program held the card, it stays on the processor — minutes per
     * answer instead of seconds — until Ollama unloads it, which a steady flow of questions
     * prevents. Nothing else shows it: Sherlock still answers, only slowly.
     */
    public record ModelPlacement(boolean loaded, long size, long sizeVram) {

        static ModelPlacement of(JsonNode ps, String model) {
            for (JsonNode m : ps.path("models")) {
                String name = m.path("name").asText(m.path("model").asText(""));
                if (name.equals(model) || name.equals(model + ":latest")) {
                    return new ModelPlacement(true, m.path("size").asLong(), m.path("size_vram").asLong());
                }
            }
            return new ModelPlacement(false, 0, 0);
        }

        /** Share of the model in GPU memory, 0 to 100. Fully on the GPU, Ollama reports size_vram == size. */
        public int gpuPercent() {
            return size <= 0 ? 0 : (int) Math.min(100, Math.floor(100.0 * sizeVram / size));
        }

        /**
         * Loaded, and not entirely in GPU memory, where a GPU is expected: on a card that holds
         * part of it — the rest spilled to the processor — or on another machine, which is there
         * for its GPU. A model entirely on this server's processor is a CPU install, not a fault.
         */
        public boolean offGpu(boolean ollamaElsewhere) {
            return loaded && sizeVram < size && (sizeVram > 0 || ollamaElsewhere);
        }
    }

    /**
     * What retrieval searches with: the discriminating terms, and the question restated so that
     * it stands on its own.
     *
     * @param searchQuestion the question with its references resolved — "Je parlais de Maxime
     *                       Roumagnac" becomes "Qui est Maxime Roumagnac ?". This is what gets
     *                       embedded for the kNN leg. Synthesis still receives the user's own
     *                       words; only retrieval sees the restatement.
     */
    public record ExpandedQuery(List<String> keywords, String searchQuestion) {}

    public List<String> expandQuery(String question) {
        return expandQuery(question, List.of()).keywords();
    }

    /**
     * Expands one question into search terms, resolving what a follow-up refers to.
     *
     * <p>A follow-up is a fragment: "Je parlais de Maxime Roumagnac" carries no question at all.
     * Retrieval used to run on those five words alone, so the kNN leg embedded a generic French
     * sentence about a person and returned generic French prose about people — which is how a
     * page about a retired farmer came back for a question about someone else entirely. The
     * earlier turn is what makes the fragment a question; it belongs in retrieval, not only in
     * the synthesis prompt.
     */
    public ExpandedQuery expandQuery(String question, List<ConversationTurn> history) {
        if (!config.isEnabled()) return new ExpandedQuery(fallbackKeywords(question), question);
        try {
            String prompt = """
                    Tu es un assistant de recherche documentaire.
                    Extrais de la question suivante les termes DISCRIMINANTS pour interroger un moteur de recherche full-text.
                    Règles strictes :
                    - Garde uniquement les noms propres, événements précis, termes techniques ou concepts spécifiques.
                    - Supprime les mots génériques : "date", "lieu", "information", "document", "quand", "comment", "qui", "quel".
                    - Maximum 5 termes. Moins c'est mieux si les termes sont précis.
                    - Garde groupés les termes qui vont ensemble : "Stade de France" est UN terme, pas trois.
                    """
                    + (history.isEmpty() ? "" : """
                    Reformule aussi la question pour qu'elle se comprenne SEULE, sans l'historique :
                    remplace les renvois ("il", "celui-ci", "je parlais de…") par ce qu'ils désignent.
                    N'ajoute aucun sujet dont la question ne parle pas.
                    """)
                    + """
                    Réponds UNIQUEMENT en JSON valide avec le format: {"question": "question reformulée", "keywords": ["terme1", "terme2"]}
                    Ne fournis aucun autre texte, uniquement le JSON.
                    """
                    + historyBlock(history)
                    + "Question: " + question;

            String responseText = generate(prompt, true);
            JsonNode parsed = MAPPER.readTree(responseText);
            JsonNode keywords = parsed.path("keywords");

            List<String> result = new ArrayList<>();
            if (keywords.isArray()) {
                String source = question + " " + parsed.path("question").asText("") + " " + historyBlock(history);
                keywords.forEach(k -> {
                    if (grounded(k.asText(), source)) result.add(k.asText());
                    else log.info("Dropped keyword {}: not in the question", k.asText());
                });
            }
            return new ExpandedQuery(result.isEmpty() ? fallbackKeywords(question) : result,
                    searchQuestion(question, parsed.path("question").asText("")));

        } catch (Exception e) {
            log.warn("LLM query expansion failed, falling back to raw question: {}", e.getMessage());
            return new ExpandedQuery(fallbackKeywords(question), question);
        }
    }

    /**
     * Whether a keyword comes from the question (or, for a follow-up, the conversation), all its
     * words in it. The model copies from its own prompt: the example name of the grouping rule
     * came back as a keyword for questions about a taxi and a drug arrest (Sept. 2026).
     */
    static boolean grounded(String keyword, String source) {
        String haystack = " " + foldForMatching(source).replaceAll("[^\\p{L}\\p{N} ]", " ") + " ";
        String words = foldForMatching(keyword).replaceAll("[^\\p{L}\\p{N} ]", " ").strip();
        if (words.isEmpty()) return false;
        for (String w : words.split("\\s+")) {
            if (!haystack.contains(" " + w + " ") && !haystack.contains(" " + w)) return false;
        }
        return true;
    }

    /**
     * The restated question, or the original when the model gave nothing usable. A rewrite that
     * runs away — the model answering instead of restating — would be embedded as the query, so
     * anything far longer than the question it replaces is refused.
     */
    static String searchQuestion(String question, String rewritten) {
        if (rewritten == null || rewritten.isBlank()) return question;
        String trimmed = rewritten.strip();
        return trimmed.length() > Math.max(200, question.length() * 3) ? question : trimmed;
    }

    private static String historyBlock(List<ConversationTurn> history) {
        if (history == null || history.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("Historique de la conversation :\n");
        for (ConversationTurn turn : history) {
            sb.append("Q : ").append(turn.question()).append("\n");
            // The answer is there to resolve a reference, not to be searched: its opening carries
            // the subject, and the rest would crowd out the question in the expansion prompt.
            String answer = turn.answer() == null ? "" : turn.answer().strip();
            if (answer.length() > 600) answer = answer.substring(0, 600) + "…";
            sb.append("R : ").append(answer).append("\n\n");
        }
        return sb.toString();
    }

    public String synthesizeAnswer(String question, List<SherlockResult> snippets) {
        return synthesizeAnswer(question, snippets, List.of(), List.of());
    }

    public String synthesizeAnswer(String question, List<SherlockResult> snippets, List<ConversationTurn> history) {
        return synthesizeAnswer(question, snippets, history, List.of());
    }

    /**
     * Writes the answer from the passages retrieval selected.
     *
     * @param terms the question's discriminating terms, from {@link #expandQuery}. A passage
     *              carrying none of them is marked as such in the context. It is not dropped —
     *              the reranker's scores do not separate a passage about the wrong subject from
     *              a passage about the right one (measured Sept. 2026: the same page scored
     *              0.0027 for a question naming someone else and 0.0031 for one naming its own
     *              subject), so a cut on the score silences right answers as readily as wrong
     *              ones. Whether the page carries the name is the signal the score is missing,
     *              and the model is told to weigh it.
     */
    public String synthesizeAnswer(String question, List<SherlockResult> snippets,
                                   List<ConversationTurn> history, List<String> terms) {
        if (!config.isEnabled()) return "(Service LLM désactivé)";
        // Nothing retrieved is a real answer, and the only one the corpus supports. Asking the
        // model to produce it from an empty context is a round trip whose outcome is already known.
        if (snippets == null || snippets.isEmpty()) return NOT_FOUND;
        try {
            StringBuilder context = new StringBuilder();
            int ungrounded = 0;
            for (int i = 0; i < snippets.size(); i++) {
                SherlockResult s = snippets.get(i);
                String label = s.page() != null ? s.filename() + ", page " + s.page() : s.filename();
                boolean grounded = mentionsAny(s.context(), terms);
                if (!grounded) ungrounded++;
                context.append("[DOCUMENT ").append(i + 1).append(" - ").append(label).append("]")
                        .append(grounded ? "" : " (ne contient aucun terme de la question)").append("\n")
                        .append(s.path()).append("\n")
                        .append(s.context()).append("\n\n");
            }
            if (ungrounded > 0) {
                log.info("{} of {} passage(s) carry none of the question's terms {}", ungrounded, snippets.size(), terms);
            }

            StringBuilder historySection = new StringBuilder();
            if (!history.isEmpty()) {
                historySection.append("Voici l'historique de la conversation :\n");
                for (ConversationTurn turn : history) {
                    historySection.append("Q : ").append(turn.question()).append("\n");
                    historySection.append("R : ").append(turn.answer()).append("\n\n");
                }
            }

            StringBuilder validRefs = new StringBuilder();
            for (int i = 0; i < snippets.size(); i++) {
                SherlockResult s = snippets.get(i);
                String label = s.page() != null ? s.filename() + ", page " + s.page() : s.filename();
                validRefs.append("  [DOCUMENT ").append(i + 1).append(" - ").append(label).append("]\n");
            }

            String historyBlock = history.isEmpty() ? "" :
                    "<history>\n" + historySection + "</history>\n\n";

            String prompt =
                    "<system_role>\n" +
                    "Tu es un bibliothécaire technique expert. Ta mission est de répondre aux questions en utilisant uniquement le contexte fourni.\n" +
                    "</system_role>\n\n" +
                    historyBlock +
                    "<context>\n" + context + "</context>\n\n" +
                    "<instructions>\n" +
                    "1. RÉPONSE BASÉE SUR LE CONTEXTE : Réponds uniquement en te basant sur les extraits dans <context>. N'utilise JAMAIS tes connaissances générales.\n" +
                    "2. GESTION DE L'ABSENCE : Si la réponse ne figure pas dans le contexte, réponds explicitement : '" + NOT_FOUND + "'\n" +
                    "3. CITATIONS OBLIGATOIRES : Appuie chaque affirmation factuelle sur un court passage de l'extrait recopié mot pour mot entre guillemets « », immédiatement suivi de sa source avec son numéro et nom exact (ex : le marché a été attribué en mars : « le marché a été attribué le 12 mars » [DOCUMENT 2 - rapport.pdf]). Ne modifie, ne résume et ne corrige jamais le texte entre guillemets ; si tu dois le couper, marque la coupure par « … ». Ces citations sont vérifiées automatiquement. La réponse n'a pas besoin de figurer telle quelle dans une phrase : si elle se déduit directement d'un passage (la question emploie d'autres mots, ou le passage répond sans reprendre la question), donne-la en citant le passage d'où tu la déduis.\n" +
                    "4. SOURCES AUTORISÉES : Tu ne peux citer que les documents suivants :\n" + validRefs + "\n" +
                    "5. LANGUE : Réponds impérativement dans la langue de la question de l'utilisateur.\n" +
                    "6. DOCUMENTS HORS SUJET : Les documents ci-dessus sont le résultat d'une recherche, pas une sélection sûre. Certains peuvent ne rien avoir à voir avec la question. Ignore ceux-là au lieu de t'en servir ; un document marqué « ne contient aucun terme de la question » ne parle probablement pas du sujet demandé.\n" +
                    "7. À QUI SONT LES FAITS : N'attribue JAMAIS au sujet de la question ce qu'un extrait dit de quelqu'un d'autre. Si un extrait décrit une personne sans la nommer (« il », « elle », « ce dernier »), ne suppose pas qu'il s'agit du sujet de la question : ne reprends ces faits que si l'extrait lui-même donne le nom.\n" +
                    "8. RÉPONSE PARTIELLE : Si le contexte ne répond qu'à une partie de la question, donne cette partie et dis franchement ce que tu n'as pas trouvé. N'ajoute jamais des faits d'un autre sujet pour étoffer la réponse.\n" +
                    "</instructions>\n\n" +
                    "<user_query>\n" + question + "\n</user_query>";

            warnIfNearContextLimit(prompt, snippets.size(), history.size());
            String answer = generate(prompt, false);
            return answer.isBlank() ? "Aucune réponse générée." : answer;

        } catch (Exception e) {
            log.error("LLM answer synthesis failed: {}", e.getMessage());
            throw new RuntimeException("LLM synthesis failed: " + e.getMessage(), e);
        }
    }

    /**
     * Builds the body for a streaming Ollama {@code /api/chat} request from a single user
     * prompt.
     *
     * <p>Thinking is hard-disabled ({@code "think": false}). For grounded RAG — extracting and
     * citing facts from the supplied context — chain-of-thought adds latency, not accuracy, so
     * it is an internal implementation detail rather than an admin-tunable knob. {@code think}
     * is only honoured on {@code /api/chat} (on {@code /api/generate} it is ignored for
     * Qwen3.5+, which then burns the token budget thinking and returns an empty answer);
     * non-thinking models such as {@code qwen2.5:7b} ignore the flag harmlessly. Thinking stays
     * off the admin's list of knobs entirely.
     */
    String chatRequestBody(String prompt, boolean jsonFormat, String model, boolean onThisServer) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", List.of(Map.of("role", "user", "content", prompt)));
        body.put("stream", true);
        body.put("think", false);
        // llm.numCtx defaults to 8192 and is sent on every call. Sending nothing does not get the
        // model's trained window — Ollama applies OLLAMA_CONTEXT_LENGTH (4096 in 0.24) to every
        // model regardless — and 4096 truncates a follow-up prompt oldest-first, silently dropping
        // the instructions that produce the citations. See LlmConfig#numCtx for the budget. Zero is
        // the explicit opt-out: send nothing and defer to the operator's Ollama environment.
        Map<String, Object> options = new LinkedHashMap<>();
        if (config.getNumCtx() > 0) {
            options.put("num_ctx", config.getNumCtx());
        }
        // Threads are for this server's processor only: a GPU node, or an Ollama on another host, is
        // another machine, and a count worked out from these cores means nothing there.
        if (onThisServer && !config.ollamaElsewhere() && config.resolvedNumThread() > 0) {
            options.put("num_thread", config.resolvedNumThread());
        }
        if (!options.isEmpty()) {
            body.put("options", options);
        }
        if (jsonFormat) {
            body.put("format", "json");
        }
        return MAPPER.writeValueAsString(body);
    }

    /**
     * POSTs a streaming request to Ollama's /api/chat and concatenates the streamed
     * tokens into the full answer. The call runs on a dedicated {@code ioExecutor} thread,
     * bounded by a hard wall-clock deadline of {@code llm.read-timeout-seconds}.
     *
     * <p>On overrun we call {@link HttpClient#shutdownNow()} on the per-call client, which
     * closes the underlying socket immediately. That TCP close is what makes Ollama cancel
     * the generation — closing only the response {@code InputStream} does not: the JDK keeps
     * the socket alive in the background (draining for connection reuse), so Ollama never
     * sees the disconnect and churns to completion for an answer nobody will read. A fresh
     * client per call keeps {@code shutdownNow()} scoped to this one request.
     */
    private String generate(String prompt, boolean jsonFormat) throws IOException, InterruptedException {
        if (fallback != null && fallback.worthTrying()) {
            try {
                String answer = generate(fallback.nodeUrl(), chatRequestBody(prompt, jsonFormat, config.resolvedGpuModel(), false));
                fallback.answered();
                return answer;
            } catch (GenerationTimeout e) {
                // It answered, too slowly: this server's processor would be slower still.
                throw e;
            } catch (IOException | RuntimeException e) {
                // Stopped by its owner, asleep, or its model missing — including in the middle of
                // an answer: this server answers the question from the start.
                fallback.failed(e);
            }
        }
        return generate(baseUrl(), chatRequestBody(prompt, jsonFormat, config.getModel(), true));
    }

    /** The wall-clock deadline was reached: the Ollama answered, only too slowly. */
    static final class GenerationTimeout extends IOException {
        GenerationTimeout(String message) { super(message); }
    }

    private String generate(String baseUrl, String requestBody) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/chat"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        Future<String> task = ioExecutor.submit(() -> {
            HttpResponse<InputStream> response =
                    client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                response.body().close();
                throw new IOException("Ollama returned HTTP " + response.statusCode());
            }
            StringBuilder answer = new StringBuilder();
            boolean done = false;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) continue;
                    JsonNode node = MAPPER.readTree(line);
                    // /api/chat streams assistant tokens under message.content (thinking,
                    // when enabled, arrives separately under message.thinking and is ignored).
                    answer.append(node.path("message").path("content").asText(""));
                    if (node.path("done").asBoolean(false)) {
                        done = true;
                        break;
                    }
                }
            }
            // Ollama stopped mid-answer — its service stopped to free the card: the first half of
            // an answer would be saved as if it were the whole of it.
            if (!done) throw new IOException("Ollama stopped before the end of its answer");
            return answer.toString();
        });

        try {
            return task.get(config.getReadTimeoutSeconds(), TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            task.cancel(true);
            throw new GenerationTimeout("Ollama generation aborted after "
                    + config.getReadTimeoutSeconds() + "s (read timeout) — the model is too slow for this limit: "
                    + "raise llm.readTimeoutSeconds in /etc/intracherche/application.yaml "
                    + "(INSTALL.md, « Sherlock ne répond pas ou est lent »)");
        } catch (InterruptedException e) {
            task.cancel(true);
            Thread.currentThread().interrupt();
            throw e;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException io) throw io;
            if (cause instanceof RuntimeException re) throw re;
            throw new IOException(cause == null ? e.getMessage() : cause.getMessage(), cause);
        } finally {
            // shutdownNow() (not close(), which would block until the request finishes)
            // closes the socket so Ollama cancels an in-flight generation; harmless no-op
            // once the request has already completed.
            client.shutdownNow();
        }
    }

    private String baseUrl() {
        return trimSlash(config.getOllamaUrl());
    }

    private static String trimSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /**
     * Says how much of the context window the prompt takes, and warns before it stops fitting.
     *
     * <p>Overflowing num_ctx is the one failure here with no symptom of its own: Ollama drops
     * the oldest tokens without a word, which takes out the instructions and the first
     * documents, and the answer simply comes back worse. Roughly three characters to the token
     * in French — approximate, but enough to catch a prompt that has outgrown the window after
     * llm.maxPassageChars or llm.maxSnippets was raised.
     */
    private void warnIfNearContextLimit(String prompt, int passages, int priorTurns) {
        if (config.getNumCtx() <= 0) return;
        int estimatedTokens = prompt.length() / 3;
        if (estimatedTokens > config.getNumCtx() * 0.8) {
            log.warn("Sherlock prompt is about {} tokens for a context window of {} ({} passages, {} prior turn(s)). "
                    + "Ollama truncates oldest-first and silently: lower llm.maxPassageChars or llm.maxSnippets, "
                    + "or raise llm.numCtx.", estimatedTokens, config.getNumCtx(), passages, priorTurns);
        } else {
            log.debug("Sherlock prompt: ~{} tokens of {} ({} passages, {} prior turn(s))",
                    estimatedTokens, config.getNumCtx(), passages, priorTurns);
        }
    }

    /**
     * Whether a passage carries any of the question's discriminating terms, ignoring case,
     * accents and the hyphens and apostrophes that vary between a question and a document
     * ("Saint-Michel" / "Saint Michel", "l'élevage" / "elevage").
     *
     * <p>With no terms to look for, every passage counts as carrying them: the mark exists to
     * warn, never to condemn a passage on missing information.
     */
    static boolean mentionsAny(String text, List<String> terms) {
        if (terms == null || terms.isEmpty()) return true;
        if (text == null || text.isBlank()) return false;
        String haystack = foldForMatching(text);
        return terms.stream()
                .map(LlmService::foldForMatching)
                .filter(t -> !t.isBlank())
                .anyMatch(haystack::contains);
    }

    private static String foldForMatching(String text) {
        String folded = Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT);
        // Collapse what only ever separates words, so one spelling of a name matches the other.
        return folded.replaceAll("[\\s'\u2019_-]+", " ").strip();
    }

    private List<String> fallbackKeywords(String question) {
        return Arrays.stream(question.split("\\s+"))
                .filter(w -> w.length() > 3)
                .limit(10)
                .collect(Collectors.toList());
    }
}
