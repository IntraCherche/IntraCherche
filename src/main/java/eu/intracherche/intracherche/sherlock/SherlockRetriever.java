package eu.intracherche.intracherche.sherlock;

import eu.intracherche.intracherche.common.DenyTokensCapability;
import eu.intracherche.intracherche.config.IndexesConfig;
import eu.intracherche.intracherche.config.LlmConfig;
import eu.intracherche.intracherche.config.RerankerConfig;
import eu.intracherche.intracherche.config.SolrConfig;
import eu.intracherche.intracherche.services.EmbeddingService;
import eu.intracherche.intracherche.services.RerankerService;
import eu.intracherche.intracherche.web.SpellingSuggestion;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrRequest;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.request.CoreAdminRequest;
import org.apache.solr.client.solrj.request.QueryRequest;
import org.apache.solr.client.solrj.util.ClientUtils;
import org.apache.solr.client.solrj.response.CoreAdminResponse;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.params.CoreAdminParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Finds the passages Sherlock hands to the LLM: hybrid BM25 + kNN retrieval over the question's
 * scope, then cross-encoder reranking down to {@code llm.maxSnippets}.
 *
 * <p>Split out of {@link SherlockWorker} so the retrieval evaluation
 * ({@code SherlockRetrievalEvalTest}) runs exactly this code with different {@link Settings}.
 */
public class SherlockRetriever {

    private static final Logger log = LoggerFactory.getLogger(SherlockRetriever.class);

    /** Both words of a two-word entity; all but one of a longer query. See {@link #buildBm25Query}. */
    private static final String RELEVANT_TERMS_REQUIRED = "2<-1";
    /** The fallback when nothing carries enough of them: any one word, the old behaviour. */
    private static final String ONE_TERM_REQUIRED = "1";

    /**
     * @param candidates     passages retrieved before reranking
     * @param maxSnippets    passages handed to the LLM, at most
     * @param vectorOnlyHits keep passages found by kNN alone. Production always does; the switch
     *                       exists so the evaluation can still measure the old BM25-only pool.
     * @param onGpu          whether the reranker reports a GPU: reading a long document whole is
     *                       then a matter of seconds, not minutes, and the user is not warned
     */
    public record Settings(int candidates, int maxSnippets, boolean vectorOnlyHits, boolean onGpu) {
        public Settings(int candidates, int maxSnippets, boolean vectorOnlyHits) {
            this(candidates, maxSnippets, vectorOnlyHits, false);
        }

        public static Settings from(LlmConfig llmConfig, RerankerConfig rerankerConfig,
                                    boolean rerankerEnabled, boolean rerankerOnGpu) {
            return new Settings(
                    rerankerEnabled ? rerankerConfig.resolvedCandidates(rerankerOnGpu) : llmConfig.getMaxSnippets(),
                    llmConfig.getMaxSnippets(),
                    true,
                    rerankerEnabled && rerankerOnGpu);
        }
    }

    /**
     * How a question on one document reads it. Every page is cut into windows the reranker
     * scores; WHOLE: all of them in one reranker call. IN_BATCHES: all of them, in several calls:
     * the same answer, only slower. PARTIAL: too many windows to score in reasonable time, or no
     * reranker to score them; only the pages a search inside the document finds are read.
     */
    public enum DocumentReading { WHOLE, IN_BATCHES, PARTIAL }

    /** A document's readable pages, the windows they cut into, and how a question will read them. */
    public record DocumentPlan(long pages, long windows, DocumentReading reading) {}

    /**
     * Windows a question on one document scores, at most. The reranker takes about 2.6 s a window
     * on the dev laptop's CPU (Sept. 2026: 154 windows in 396 s), so 300 bound a question at
     * ~13 minutes there; a GPU scores 1 500 in well under a minute.
     */
    static final int MAX_DOCUMENT_WINDOWS_CPU = 300, MAX_DOCUMENT_WINDOWS_GPU = 1500;

    /** One retrieved passage: its 1-based position after RRF, and its reranker score if scored. */
    public record Candidate(SherlockResult result, int retrievalRank, Double rerankScore) {}

    /** Every candidate, best first, and the subset that goes to the LLM. */
    public record Retrieval(List<Candidate> candidates, List<SherlockResult> selected) {}

    private final SolrClient solrClient;
    private final SolrConfig solrConfig;
    private final IndexesConfig indexesConfig;
    private final LlmConfig llmConfig;
    private final RerankerService rerankerService;
    private final EmbeddingService embeddingService;

    public SherlockRetriever(SolrClient solrClient, SolrConfig solrConfig, IndexesConfig indexesConfig,
                             LlmConfig llmConfig, RerankerService rerankerService,
                             EmbeddingService embeddingService) {
        this.solrClient = solrClient;
        this.solrConfig = solrConfig;
        this.indexesConfig = indexesConfig;
        this.llmConfig = llmConfig;
        this.rerankerService = rerankerService;
        this.embeddingService = embeddingService;
    }

    /**
     * Retrieves candidates for one question, reranks them, and selects what the LLM will read.
     *
     * <p>Throws rather than returning an empty list when retrieval itself fails. An empty list
     * is a real answer — the corpus has nothing on the subject — and the LLM turns it into
     * "Je ne trouve pas cette information dans les documents fournis." A Solr error is not that:
     * swallowing it hands the model an empty context and dresses an outage up as a confident
     * negative. Let it propagate so the question is marked FAILED.
     *
     * <p>The same holds for a scope that no longer matches anything (every requested index was
     * removed since the question was asked): searching nothing would also answer "not found".
     */
    public Retrieval retrieve(String question, List<String> keywords, List<String> userSids,
                              List<String> requestedIndices, Settings settings) throws Exception {
        return retrieve(question, keywords, userSids, requestedIndices, null, settings);
    }

    /**
     * @param documentId the file ({@code parent_id}) the question is about, or null for the whole
     *                   scope. It only narrows the filters: the access and index filters still
     *                   apply, so no id can reach a page the user may not read.
     */
    public Retrieval retrieve(String question, List<String> keywords, List<String> userSids,
                              List<String> requestedIndices, String documentId, Settings settings) throws Exception {
        List<SherlockResult> retrieved = retrieveCandidates(question, keywords, userSids, requestedIndices, documentId, settings);

        List<Candidate> candidates = new ArrayList<>();
        for (int i = 0; i < retrieved.size(); i++) candidates.add(new Candidate(retrieved.get(i), i + 1, null));

        if (!rerankerService.isEnabled() || candidates.size() <= settings.maxSnippets()) {
            log.info("Reranking skipped: {} snippet(s) retrieved, threshold is >{}", candidates.size(), settings.maxSnippets());
            return new Retrieval(candidates, retrieved);
        }

        // The reranker reads the page window the LLM will read, not the highlight fragments. The
        // fragments are chosen by keyword, and the sentence holding an answer often carries none:
        // "Le bilan des victimes fait état de 130 morts" names neither "attentats" nor the date.
        // On OCR text they also overrun hl.fragsize (one fragment ran 1554 chars against 400), so
        // the first one filled the reranker's budget. Scoring the fragments, the answer text was
        // in what the reranker read on 9 of 16 answer pages; scoring the window, on 15 of 16, and
        // the answer page came first 16 times out of 17 instead of 12 (Sept. 2026).
        List<Double> scores = rerankInBatches(question, retrieved.stream().map(SherlockResult::context).toList(),
                Math.max(settings.candidates(), settings.maxSnippets()));
        if (scores == null) {
            log.warn("Reranker unavailable; using top {} snippets by retrieval score", settings.maxSnippets());
            return new Retrieval(candidates, retrieved.stream().limit(settings.maxSnippets()).toList());
        }

        List<Candidate> reranked = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            reranked.add(new Candidate(retrieved.get(i), i + 1, scores.get(i)));
        }
        // Always the top maxSnippets. A cut on the score gap to the best passage was evaluated
        // (Sept. 2026) and dropped: bge-reranker scores are too poorly calibrated, and the cut
        // threw away the answering passage on 2 of 17 questions while fixing none.
        reranked.sort(Comparator.comparingDouble(Candidate::rerankScore).reversed());
        // A document read in windows has several per page: each page counts once, at its best.
        Set<String> pagesSeen = new HashSet<>();
        reranked.removeIf(c -> !pagesSeen.add(c.result().id()));
        List<SherlockResult> selected = reranked.stream().limit(settings.maxSnippets()).map(Candidate::result).toList();
        log.info("Reranked {} candidates to {} snippet(s)", reranked.size(), selected.size());
        return new Retrieval(reranked, selected);
    }

    /** Words of a question checked for spelling: enough for a long sentence, bounded for a pasted page. */
    private static final int MAX_QUESTION_WORDS = 20;

    /**
     * The question's misspelled words and their corrections, typed word to corrected word, as
     * "Vouliez-vous dire…" finds them: only a word on no page the user can read in the question's
     * indexes is corrected, into a word that is on one. A misspelled name costs Sherlock the
     * answer even when the right document is read: the reranker scores the pages against
     * "Ravena", which none of them carries (Sept. 2026: the Tavenna report read whole, answer
     * pages ranked #6 and #26 for the misspelled question, #1 for the right one).
     *
     * <p>The check covers the indexes, never a single document: a word the document lacks is
     * not a misspelling. Best effort: a failure costs the corrections, never the question.
     */
    public Map<String, String> spellingCorrections(String question, List<String> userSids,
                                                   List<String> requestedIndices) {
        try {
            List<String> coreNames = scopeCoreNames(visibleCoreNames(userSids), requestedIndices);
            String[] filters = buildFilterQueries(coreNames, userSids).toArray(String[]::new);
            String shards = buildShards(coreNames);
            // Questions carry punctuation that queries do not: "Tavenna," is not a word to the checker.
            List<String> words = SpellingSuggestion.words(
                    question.replaceAll("[^\\p{L}\\p{N}'’\\-\\s]+", " "), MAX_QUESTION_WORDS);
            Map<String, String> corrections = new LinkedHashMap<>();
            for (String word : SpellingSuggestion.unmatched(solrClient, words, filters, shards, coreNames)) {
                List<String> context = words.stream()
                        .filter(w -> !w.equals(word) && !QUESTION_WORDS.contains(SpellingSuggestion.fold(w)))
                        .toList();
                bestInContext(word, SpellingSuggestion.candidates(solrClient, word, filters, coreNames, 5),
                        context, filters, shards, coreNames.get(0))
                        .ifPresent(c -> corrections.put(word, c));
            }
            return corrections;
        } catch (Exception e) {
            log.warn("Spelling check of a Sherlock question failed ({}), asked as typed", e.getMessage());
            return Map.of();
        }
    }

    /**
     * Grammar words a question carries whatever it asks: they appear next to any candidate, so
     * they say nothing about which one the question means.
     */
    private static final Set<String> QUESTION_WORDS = Set.of(
            "quand", "dans", "avec", "pour", "elle", "elles", "sont", "cette", "etait", "entre", "leur",
            "leurs", "quel", "quelle", "quels", "quelles", "comment", "pourquoi", "combien", "depuis",
            "etre", "avoir", "fait", "faire", "tous", "toutes", "plus", "moins", "sous", "vers", "chez",
            "a-t-il", "a-t-elle", "est-ce", "est-il", "est-elle", "quoi", "dont", "avait", "ont-ils");
    /** Share of a candidate's pages that must also carry another word of the question. */
    private static final double MIN_SHARE_IN_CONTEXT = 0.1;

    /**
     * Of the spellchecker's candidates for a word, the one the rest of the question is about.
     *
     * <p>The search bar takes the closest spelling, which is right for a query the user then
     * reads, but not for a question answered without the user choosing: "Ravena" is closest to
     * "rehana", a Wikipedia name, while the question is about Tavenna. The rest of the question
     * tells candidates apart, as the share of each one's readable pages that also carry another
     * of its words; not the count, which a common word wins next to anything ("Fauvilet": gobelet,
     * 2 pages in context out of 22, against fauvillet, 1 out of 2). The share is weighted by how
     * close the spelling is, so a variant the corpus also misspells ("abdelslam", 4 of 4) does
     * not beat the word itself ("abdeslam", 64 of 66).
     *
     * <p>A candidate that adds an apostrophe, hyphen, space or digit the user did not type is
     * another word ("Lyana" to "l'an"; see {@link #changesKind}). Below {@link #MIN_SHARE_IN_CONTEXT} no candidate is trusted, and
     * the word is searched as typed rather than as someone else: on the evaluation's eight
     * misspellings (Sept. 2026) six were corrected and "Ravena" and "Lyana" were left alone,
     * where the closest spelling turned four of them into another word.
     */
    private Optional<String> bestInContext(String word, List<String> candidates, List<String> context,
                                           String[] filters, String shards, String core) {
        if (context.isEmpty()) return Optional.empty();
        String typed = SpellingSuggestion.fold(word);
        String contextClause = context.stream().map(ClientUtils::escapeQueryChars).collect(Collectors.joining(" "));
        String best = null;
        double bestScore = 0;
        for (String candidate : candidates) {
            if (changesKind(word, candidate)) continue;
            String term = "\"" + ClientUtils.escapeQueryChars(candidate) + "\"";
            try {
                long pages = countPages(term, filters, shards, core);
                long inContext = countPages("+" + term + " +(" + contextClause + ")", filters, shards, core);
                double share = pages == 0 ? 0 : (double) inContext / pages;
                if (inContext == 0 || share < MIN_SHARE_IN_CONTEXT) continue;
                double score = share * SpellingSuggestion.similarity(typed, SpellingSuggestion.fold(candidate));
                if (score > bestScore) {
                    best = candidate;
                    bestScore = score;
                }
            } catch (Exception e) {
                log.warn("Counting candidate {} in context failed ({})", candidate, e.getMessage());
            }
        }
        return Optional.ofNullable(best);
    }

    private long countPages(String query, String[] filters, String shards, String core) throws Exception {
        SolrQuery q = new SolrQuery(query);
        q.setParam("df", "content");
        q.setParam("q.op", "OR");
        q.setRows(0);
        for (String fq : filters) q.addFilterQuery(fq);
        setShards(q, shards);
        return new QueryRequest(q, SolrRequest.METHOD.POST).process(solrClient, core).getResults().getNumFound();
    }

    /**
     * Whether a candidate is another kind of word than the one typed: one with an apostrophe,
     * hyphen or space, or a digit, the user did not type. "l'ENOB" is on no page, and "l'eno2"
     * sent the question after ENO2 (Sept. 2026).
     */
    static boolean changesKind(String typed, String candidate) {
        for (String separator : List.of("'", "’", "-", " ")) {
            if (candidate.contains(separator) && !typed.contains(separator)) return true;
        }
        return candidate.chars().anyMatch(Character::isDigit) && typed.chars().noneMatch(Character::isDigit);
    }

    /** {@code text} with each corrected word replaced where it stands as a whole word, in the case it was typed in. */
    public static String applyCorrections(String text, Map<String, String> corrections) {
        String corrected = text;
        for (Map.Entry<String, String> c : corrections.entrySet()) {
            Matcher m = Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(c.getKey()) + "(?![\\p{L}\\p{N}])").matcher(corrected);
            corrected = m.replaceAll(Matcher.quoteReplacement(SpellingSuggestion.matchCase(c.getKey(), c.getValue())));
        }
        return corrected;
    }

    /**
     * Scores every passage, {@code batch} at a time: the reranker refuses more than its
     * {@code max_passages} in one call, and a document read whole has more pages than that.
     * Null when any batch fails, as for a single call: scores from part of the pool cannot be
     * ranked against the rest.
     */
    /**
     * How a question on {@code documentId} will read it. Asked before the question is searched,
     * so the user learns a long document will be slow, or read in part, while waiting rather
     * than after.
     */
    public DocumentPlan planDocument(String documentId, List<String> userSids, List<String> requestedIndices,
                                     Settings settings) throws Exception {
        List<String> coreNames = scopeCoreNames(visibleCoreNames(userSids), requestedIndices);
        if (coreNames.isEmpty()) return new DocumentPlan(0, 0, DocumentReading.WHOLE);
        List<String> filters = buildFilterQueries(coreNames, userSids);
        filters.add("parent_id:" + ClientUtils.escapeQueryChars(documentId));
        return plan(documentPages(filters, buildShards(coreNames), coreNames.get(0), settings), settings);
    }

    /** A document's pages, whole: enough to cut every window a question may score, and one more. */
    private SolrDocumentList documentPages(List<String> filters, String shards, String core, Settings settings)
            throws Exception {
        return new QueryRequest(buildDocumentQuery(filters, shards, maxWindows(settings) + 1), SolrRequest.METHOD.POST)
                .process(solrClient, core).getResults();
    }

    private static int maxWindows(Settings settings) {
        return settings.onGpu() ? MAX_DOCUMENT_WINDOWS_GPU : MAX_DOCUMENT_WINDOWS_CPU;
    }

    private DocumentPlan plan(SolrDocumentList pages, Settings settings) {
        long found = pages == null ? 0 : pages.getNumFound();
        // More pages than windows allowed: too long, whatever the pages hold
        long windows = pages == null ? 0 : found > pages.size() ? -1
                : pages.stream().mapToLong(p -> pageWindows(content(p), llmConfig.getMaxPassageChars()).size()).sum();
        DocumentReading reading;
        if (!rerankerService.isEnabled() || windows < 0 || windows > maxWindows(settings)) reading = DocumentReading.PARTIAL;
        else if (windows <= settings.candidates()) reading = DocumentReading.WHOLE;
        else reading = DocumentReading.IN_BATCHES;
        return new DocumentPlan(found, windows, reading);
    }

    private static String content(SolrDocument doc) {
        Object raw = doc.getFieldValue("content");
        return raw == null ? "" : raw.toString();
    }

    /**
     * A page cut into consecutive windows of at most {@code budget} characters, overlapping by a
     * seventh so a sentence cut by one is whole in the next. A page that fits is one window.
     *
     * <p>A question on one document scores every window and keeps each page's best, instead of
     * placing one window by the highlighter. Placing it was a guess that moved with the exact
     * words: on the Tavenna report (38 of 46 pages longer than the budget) the right spelling of
     * the name put page 8's window past the answer, ranking it #34, while the first 1 800
     * characters of each page ranked it #1 for either spelling (Sept. 2026).
     */
    static List<String> pageWindows(String full, int budget) {
        if (full == null || full.isBlank()) return List.of();
        if (budget <= 0 || full.length() <= budget) return List.of(full);
        List<String> windows = new ArrayList<>();
        int step = budget - budget / 7;
        for (int start = 0; start < full.length(); start += step) {
            int s = start, end = Math.min(full.length(), start + budget);
            if (s > 0) {
                int space = full.indexOf(' ', s);
                if (space >= 0 && space - s < 200) s = space + 1;
            }
            if (end < full.length()) {
                int space = full.lastIndexOf(' ', end);
                if (space > s && end - space < 200) end = space;
            }
            windows.add((s > 0 ? "[…] " : "") + full.substring(s, end).strip() + (end < full.length() ? " […]" : ""));
            if (end >= full.length()) break;
        }
        return windows;
    }

    private List<Double> rerankInBatches(String question, List<String> passages, int batch) {
        if (passages.size() <= batch) return rerankerService.rerank(question, passages);
        List<Double> scores = new ArrayList<>(passages.size());
        long t0 = System.currentTimeMillis();
        for (int from = 0; from < passages.size(); from += batch) {
            List<Double> part = rerankerService.rerank(question, passages.subList(from, Math.min(passages.size(), from + batch)));
            if (part == null) return null;
            scores.addAll(part);
        }
        log.info("Reranked {} passages in batches of {} in {} s", passages.size(), batch, (System.currentTimeMillis() - t0) / 1000);
        return scores;
    }

    private List<SherlockResult> retrieveCandidates(String question, List<String> keywords, List<String> userSids,
                                                    List<String> requestedIndices, String documentId,
                                                    Settings settings) throws Exception {
        List<String> coreNames = scopeCoreNames(visibleCoreNames(userSids), requestedIndices);

        if (coreNames.isEmpty()) {
            throw new IllegalStateException(requestedIndices == null || requestedIndices.isEmpty()
                    ? "No searchable Solr core — nothing in indexes.yaml exists in Solr"
                    : "None of the requested indexes is searchable: " + requestedIndices);
        }

        String primaryCore = coreNames.get(0);
        String queryStr = bm25Query(keywords);
        List<String> filterQueries = buildFilterQueries(coreNames, userSids);
        String shards = buildShards(coreNames);

        if (documentId != null) {
            filterQueries.add("parent_id:" + ClientUtils.escapeQueryChars(documentId));
            // A document that fits the reranker is read whole: every page is a candidate, and no
            // keyword decides which. Keywords are what lost the live Tavenna question (Sept. 2026):
            // the draw [affaire Tavenna] matched every page's running header and none of the three
            // answer pages, while reranking all the report's pages put page 8 first.
            SolrDocumentList pages = documentPages(filterQueries, shards, primaryCore, settings);
            if (pages == null || pages.getNumFound() == 0) {
                // Deleted since, moved out of the scope, or never readable by this user. "Not
                // found in the document" would be a false answer: fail the question instead.
                throw new IllegalStateException("Document " + documentId + " is not searchable for this user");
            }
            DocumentPlan plan = plan(pages, settings);
            if (plan.reading() != DocumentReading.PARTIAL) {
                List<SherlockResult> windows = new ArrayList<>();
                Set<String> seen = new HashSet<>();
                for (SolrDocument page : pages) {
                    SolrDocumentList one = new SolrDocumentList();
                    one.add(page);
                    SherlockResult head = toResults(one, null).stream().findFirst().orElse(null);
                    if (head == null) continue;
                    for (String window : pageWindows(content(page), llmConfig.getMaxPassageChars())) {
                        // The same file in two cores: one copy of each window
                        if (seen.add(head.filename() + "\u0000" + head.page() + "\u0000" + window)) {
                            windows.add(new SherlockResult(head.id(), head.path(), head.filename(), window, window, head.page()));
                        }
                    }
                }
                log.info("Question on one document: {} page(s) cut into {} window(s), all scored", plan.pages(), windows.size());
                return windows;
            }
            log.info("Question on one document of {} pages, too many windows to score: searching within it", plan.pages());
        }

        if (embeddingService.isEnabled()) {
            float[] queryVector = embeddingService.embed(question, true);
            if (queryVector != null) {
                return hybridRetrieve(primaryCore, question, queryStr, keywords, queryVector, filterQueries, shards, settings);
            }
            log.warn("Embedding failed for Sherlock question — falling back to BM25");
        }

        SolrQuery bm25Q = buildBm25Query(queryStr, keywords, filterQueries, shards, settings.candidates());
        QueryResponse response = queryBm25(primaryCore, bm25Q);
        return toResults(response.getResults(), response.getHighlighting());
    }

    /**
     * Narrows the live cores to the question's scope. An empty scope means every live core.
     * Only names already known to Solr survive, so a stored scope never reaches {@code fq} or
     * {@code shards} unchecked.
     */
    static List<String> scopeCoreNames(List<String> liveCores, List<String> requestedIndices) {
        if (requestedIndices == null || requestedIndices.isEmpty()) return liveCores;
        Set<String> requested = new HashSet<>(requestedIndices);
        return liveCores.stream().filter(requested::contains).toList();
    }

    /** The live cores the asking user may see (indexes.yaml's visibleToGroups). */
    private List<String> visibleCoreNames(List<String> userSids) throws SolrServerException, IOException {
        return indexesConfig.visibleCores(liveCoreNames(), userSids == null ? List.of() : userSids);
    }

    /**
     * The cores from indexes.yaml that actually exist in Solr, in configured order.
     *
     * <p>indexes.yaml is a declaration of intent, not an inventory: it also carries tombstones
     * (entries left at {@code status: DELETE} so the cleanup path runs once more) whose cores are
     * already gone. Naming a missing core in {@code shards} makes Solr 404 the whole distributed
     * query, which used to wipe out every Sherlock answer. So allowlist against Solr's own core
     * list — the same check {@code SearchController} applies before it builds {@code shards},
     * which is why plain search kept working while Sherlock did not.
     */
    private List<String> liveCoreNames() throws SolrServerException, IOException {
        CoreAdminRequest request = new CoreAdminRequest();
        request.setAction(CoreAdminParams.CoreAdminAction.STATUS);
        CoreAdminResponse response = request.process(solrClient);

        Set<String> existing = new LinkedHashSet<>();
        response.getCoreStatus().forEach((name, status) -> existing.add(name));

        List<String> configured = indexesConfig.getIndexes().stream()
                .map(idx -> idx.getCoreName())
                .collect(Collectors.toList());
        List<String> live = configured.stream()
                .filter(existing::contains)
                .collect(Collectors.toList());

        if (live.size() != configured.size()) {
            List<String> skipped = configured.stream().filter(n -> !existing.contains(n)).toList();
            log.debug("Skipping {} configured core(s) absent from Solr: {}", skipped.size(), skipped);
        }
        return live;
    }

    private List<SherlockResult> hybridRetrieve(String primaryCore, String question, String queryStr,
                                                List<String> keywords, float[] queryVector,
                                                List<String> filterQueries, String shards,
                                                Settings settings) throws Exception {
        // BM25: oversample so RRF has enough candidates to reorder
        SolrQuery bm25Q = buildBm25Query(queryStr, keywords, filterQueries, shards, 200);

        SolrQuery knnQ = new SolrQuery();
        knnQ.setQuery("{!knn f=content_vector topK=100}" + vectorToSolrString(queryVector));
        knnQ.setRows(100);
        knnQ.setFields("id");
        filterQueries.forEach(knnQ::addFilterQuery);
        setShards(knnQ, shards);

        // POST to avoid URI length limit with 768-float vectors
        QueryResponse bm25Response = queryBm25(primaryCore, bm25Q);
        QueryResponse knnResponse  = new QueryRequest(knnQ,  SolrRequest.METHOD.POST).process(solrClient, primaryCore);

        List<String> bm25Ids = bm25Response.getResults().stream()
                .map(d -> (String) d.getFieldValue("id")).toList();
        List<String> knnIds = knnResponse.getResults().stream()
                .map(d -> (String) d.getFieldValue("id")).toList();

        log.debug("Hybrid retrieval: {} BM25 hits, {} kNN hits", bm25Ids.size(), knnIds.size());

        Map<String, SherlockResult> byId = new LinkedHashMap<>();
        toResults(bm25Response.getResults(), bm25Response.getHighlighting()).forEach(r -> byId.put(r.id(), r));

        // The same file indexed in two cores (two shares, or a folder listed twice) comes back
        // once per core with identical text. Each copy took a reranker call and, worse, one of
        // the few slots handed to the LLM: the evaluation saw a page fill two of five.
        Set<String> seen = new HashSet<>();
        List<String> ranked = reciprocalRankFusion(bm25Ids, knnIds, 60).keySet().stream()
                .filter(id -> byId.containsKey(id) ? seen.add(passageKey(byId.get(id))) : settings.vectorOnlyHits())
                .limit(settings.candidates())
                .toList();

        // A passage kNN found but BM25 did not has no text yet: it was only asked for its id.
        // Without this fetch it was silently dropped, so vector search could reorder the
        // keyword hits but never contribute a passage the keywords missed.
        List<String> missing = ranked.stream().filter(id -> !byId.containsKey(id)).toList();
        if (!missing.isEmpty()) {
            SolrQuery fetchQ = buildFetchQuery(missing, question, queryStr, filterQueries, shards);
            QueryResponse fetched = new QueryRequest(fetchQ, SolrRequest.METHOD.POST).process(solrClient, primaryCore);
            toResults(fetched.getResults(), fetched.getHighlighting()).forEach(r -> byId.put(r.id(), r));
            log.debug("Fetched {} kNN-only passage(s) for the candidate pool", missing.size());
        }

        return ranked.stream()
                .filter(byId::containsKey)
                .filter(id -> !missing.contains(id) || seen.add(passageKey(byId.get(id))))
                .map(byId::get)
                .toList();
    }

    /** Same file name, page and text: a copy of one passage from another core. */
    private static String passageKey(SherlockResult r) {
        return r.filename() + "\u0000" + r.page() + "\u0000" + r.extract();
    }

    private SolrQuery buildBm25Query(String queryStr, List<String> keywords, List<String> filterQueries,
                                     String shards, int rows) {
        SolrQuery q = new SolrQuery();
        q.setParam("defType", "edismax");
        q.setParam("qf", "content");
        // Sherlock used to take Solr's default OR, so "Maxime Roumagnac" matched every page
        // carrying either word — padding the context with noise, and worse in proportion to the
        // corpus. mm="2<-1" is "both words of a two-word entity, all but one of a longer query".
        //
        // Requiring every word was measured first and is too strict: on the 17-question
        // evaluation it fixed six questions but lost one outright ("Stade de France explosion",
        // four words, answer page #1 -> gone, because that page carries three of the four).
        // Under 2<-1 the same page comes back at #9 out of 33 where OR gave #9 out of 183, and
        // "Maxime Roumagnac" still narrows from 3 pages to 1. queryBm25 relaxes further when
        // even this finds nothing, so recall never falls below the old behaviour.
        q.setParam("mm", RELEVANT_TERMS_REQUIRED);
        q.setQuery(queryStr);
        String boosts = phraseBoosts(keywords);
        if (!boosts.isBlank()) q.setParam("bq", boosts);
        q.setRows(rows);
        q.setFields("id", "path", "content", "page_number");
        filterQueries.forEach(q::addFilterQuery);
        addHighlighting(q);
        setShards(q, shards);
        return q;
    }

    /**
     * The BM25 query string for a set of expanded keywords: every word, required (see
     * {@link #buildBm25Query}), and none of them quoted.
     *
     * <p>Requiring the entity as a phrase was tried first and reverted: it does not survive OCR.
     * The scanned article that carries Maxime Roumagnac reads "CarMaxime Roumagnac" — the
     * recogniser glued two words — so the bigram does not exist and {@code "Maxime Roumagnac"}
     * matches nothing, while the two words required separately match that page and only that
     * page (3 hits with OR, 1 with AND, 0 as a phrase; measured on the demo core, Sept. 2026).
     * Adjacency belongs in {@link #phraseBoosts} instead, where it raises a page's rank without
     * being able to exclude one.
     */
    static String bm25Query(List<String> keywords) {
        if (keywords == null) return "";
        StringBuilder sb = new StringBuilder();
        for (String keyword : keywords) {
            if (keyword == null || keyword.isBlank()) continue;
            for (String word : keyword.strip().split("\\s+")) {
                if (word.isBlank()) continue;
                if (sb.length() > 0) sb.append(' ');
                sb.append(ClientUtils.escapeQueryChars(word));
            }
        }
        return sb.toString();
    }

    /**
     * A boost query preferring the pages where a multi-word entity appears as written.
     *
     * <p>"Maxime Roumagnac" as two required words still matches a page naming a different Maxime
     * next to a different Roumagnac; this puts the page that carries the whole name first. A
     * boost, never a filter: a page whose OCR broke the name apart keeps its place in the pool.
     */
    static String phraseBoosts(List<String> keywords) {
        if (keywords == null) return "";
        StringBuilder sb = new StringBuilder();
        for (String keyword : keywords) {
            if (keyword == null || !keyword.strip().contains(" ")) continue;
            // Inside a Solr phrase only \ and " carry meaning.
            String phrase = keyword.strip().replace("\\", "\\\\").replace("\"", "\\\"");
            if (sb.length() > 0) sb.append(' ');
            sb.append("content:\"").append(phrase).append("\"^3");
        }
        return sb.toString();
    }

    /**
     * Runs a BM25 query, relaxing to OR when strict matching finds nothing.
     *
     * <p>Retried only on a zero-result query, so the extra round trip is the cheapest possible
     * case. Relaxing {@code mm} rather than {@code q.op} also keeps the retry off the filter
     * queries: mm applies to the main query alone, where {@code SearchController} has to warn
     * that its q.op retry reaches the fq clauses too. Filters must never relax — only the terms
     * the expansion produced do.
     *
     * <p>Requiring the rarest word instead of relaxing to OR was measured and rejected (Sept.
     * 2026, 18 questions over the demo cores plus 35 000 Wikipedia pages): a word rare enough to
     * be chosen is often one OCR mangled, and "Amnéville", on a single page, shut out the answer
     * page that carries "Destination … Moselle" but not it. With global IDF ({@code statsCache}
     * in solrconfig.xml) plain OR already ranks the rare word's pages first, without excluding.
     */
    private QueryResponse queryBm25(String core, SolrQuery q) throws SolrServerException, IOException {
        QueryResponse response = new QueryRequest(q, SolrRequest.METHOD.POST).process(solrClient, core);
        if (response.getResults() != null && response.getResults().getNumFound() == 0
                && !q.getQuery().isBlank()) {
            // mm is set explicitly above, so q.op would not reach the query: lower mm itself.
            q.setParam("mm", ONE_TERM_REQUIRED);
            response = new QueryRequest(q, SolrRequest.METHOD.POST).process(solrClient, core);
            log.debug("No page carries enough of the keywords; relaxed to any ({} hit(s))",
                    response.getResults() == null ? 0 : response.getResults().getNumFound());
        }
        return response;
    }

    /**
     * Fetches passages by id. The access and scope filters still apply: an id list must never
     * widen what the user can read. Highlighting runs against the keywords and the question
     * itself, since these passages matched neither by keyword.
     */
    private SolrQuery buildFetchQuery(List<String> ids, String question, String queryStr,
                                      List<String> filterQueries, String shards) {
        SolrQuery q = new SolrQuery();
        q.setQuery("{!terms f=id}" + String.join(",", ids));
        q.setRows(ids.size());
        q.setFields("id", "path", "content", "page_number");
        filterQueries.forEach(q::addFilterQuery);
        addHighlighting(q);
        q.setParam("hl.q", (queryStr + " " + question).trim());
        q.setParam("hl.qparser", "edismax");
        q.setParam("qf", "content");
        setShards(q, shards);
        return q;
    }

    /**
     * Every page of one document, whole and in page order: {@link #pageWindows} cuts them, so
     * nothing here decides which part of a page is read. {@code filterQueries} carries the
     * document filter along with the access filters.
     */
    private SolrQuery buildDocumentQuery(List<String> filterQueries, String shards, int rows) {
        SolrQuery q = new SolrQuery("*:*");
        q.setRows(rows);
        q.setFields("id", "path", "content", "page_number");
        q.addSort("page_number", SolrQuery.ORDER.asc);
        filterQueries.forEach(q::addFilterQuery);
        setShards(q, shards);
        return q;
    }

    private void addHighlighting(SolrQuery q) {
        q.setHighlight(true);
        q.addHighlightField("content");
        q.setHighlightSnippets(3);
        q.setHighlightFragsize(llmConfig.getSnippetFragSize());
        q.setParam("hl.method", "unified");
    }

    private static void setShards(SolrQuery q, String shards) {
        if (shards == null) return;
        q.setParam("shards", shards);
        q.setParam("distrib", "true");
        // One unreachable shard should degrade the answer, not erase it.
        q.setParam("shards.tolerant", "true");
    }

    private List<String> buildFilterQueries(List<String> coreNames, List<String> userSids) {
        List<String> fqs = new ArrayList<>();
        fqs.add("index_source:(" + String.join(" OR ", coreNames) + ")");
        if (userSids != null && !userSids.isEmpty()) {
            String quotedSids = userSids.stream()
                    .map(sid -> "\"" + sid + "\"")
                    .collect(Collectors.joining(" OR "));
            fqs.add("accessTokens:(" + quotedSids + ")");
            // A Windows Deny ACE on any of the user's SIDs overrides an Allow ACE elsewhere in
            // their token — see the Deny-ACE fix in SearchController.addFilters for the full
            // rationale; this filter must always travel with the one above, never alone. Gated
            // on the schema having the field: see DenyTokensCapability.
            String anyCore = (coreNames != null && !coreNames.isEmpty()) ? coreNames.get(0) : null;
            if (DenyTokensCapability.isAvailable(solrClient, anyCore)) {
                fqs.add("-denyTokens:(" + quotedSids + ")");
            }
        } else {
            fqs.add("-*:*");
        }
        return fqs;
    }

    private String buildShards(List<String> coreNames) {
        if (coreNames.size() <= 1) return null;
        String solrHost = solrConfig.solrBaseUrl().replaceFirst("^https?://", "");
        return coreNames.stream()
                .map(name -> solrHost + "/" + name)
                .collect(Collectors.joining(","));
    }

    /** Turns Solr documents into passages, in response order, skipping any without text. */
    private List<SherlockResult> toResults(SolrDocumentList docs, Map<String, Map<String, List<String>>> highlighting) {
        if (docs == null) return List.of();
        List<SherlockResult> results = new ArrayList<>();
        for (SolrDocument doc : docs) {
            String docId = (String) doc.getFieldValue("id");
            String path = (String) doc.getFieldValue("path");
            if (path == null) path = docId;
            int lastSlash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
            String filename = lastSlash >= 0 ? path.substring(lastSlash + 1) : path;

            Object rawContent = doc.getFieldValue("content");
            String full = rawContent == null ? "" : rawContent.toString();

            String firstFragment = "";
            String extract = "";
            if (highlighting != null && highlighting.containsKey(docId)) {
                List<String> fragments = highlighting.get(docId).get("content");
                if (fragments != null && !fragments.isEmpty()) {
                    List<String> plain = fragments.stream().map(SherlockRetriever::stripTags).toList();
                    firstFragment = plain.get(0);
                    extract = String.join(" … ", plain);
                }
            }
            if (extract.isBlank()) {
                // kNN found this page and the keywords did not, so there is nothing to highlight.
                extract = full;
                int cap = rerankerService.isEnabled() ? 1500 : llmConfig.getSnippetFragSize();
                if (extract.length() > cap) extract = extract.substring(0, cap) + "…";
            }

            Integer page = null;
            Object pageObj = doc.getFieldValue("page_number");
            if (pageObj instanceof Number) page = ((Number) pageObj).intValue();

            String context = contextFor(full, firstFragment, llmConfig.getMaxPassageChars());
            if (context.isBlank()) context = extract;

            if (!extract.isBlank()) {
                results.add(new SherlockResult(docId, path, filename, extract, context, page));
            }
        }
        return results;
    }

    /** Solr's unified highlighter wraps matches in &lt;em&gt;; the model has no use for the markup. */
    static String stripTags(String fragment) {
        return fragment == null ? "" : fragment.replaceAll("</?em>", "");
    }

    /**
     * The contiguous run of a page that the LLM reads.
     *
     * <p>A page short enough to fit is sent whole — a Solr document here is one page, so that is
     * the usual case and it is the one that matters: whole, the page still says who its "il" is.
     * A longer page is cut to a single window around the first match, never several windows
     * spliced together. Splicing is what let a page introducing one person, then describing
     * another, come back as one paragraph about whoever the question named.
     *
     * @param anchor the first highlight fragment, used to place the window; may be empty, in
     *               which case the page's opening is taken — the head of a page carries its
     *               heading, which is what tells the model what the page is about.
     */
    static String contextFor(String full, String anchor, int budget) {
        if (full == null || full.isBlank()) return "";
        if (budget <= 0 || full.length() <= budget) return full;

        int at = anchorIndex(full, anchor);
        int start = at < 0 ? 0 : Math.max(0, at + anchor.strip().length() / 2 - budget / 2);
        int end = Math.min(full.length(), start + budget);
        start = Math.max(0, end - budget);

        // Snap inwards to whitespace so the window never opens or closes mid-word.
        if (start > 0) {
            int space = full.indexOf(' ', start);
            if (space >= 0 && space - start < 200) start = space + 1;
        }
        if (end < full.length()) {
            int space = full.lastIndexOf(' ', end);
            if (space > start && end - space < 200) end = space;
        }

        String window = full.substring(start, end).strip();
        return (start > 0 ? "[…] " : "") + window + (end < full.length() ? " […]" : "");
    }

    /**
     * Where the highlight fragment sits in the page. Falls back to a run from the middle of the
     * fragment, because a fragment can begin or end mid-word and because the highlighter does not
     * always reproduce the source's whitespace exactly.
     */
    private static int anchorIndex(String full, String anchor) {
        if (anchor == null || anchor.isBlank()) return -1;
        String needle = anchor.strip();
        int at = full.indexOf(needle);
        if (at >= 0) return at;
        if (needle.length() > 60) {
            String middle = needle.substring(needle.length() / 2 - 30, needle.length() / 2 + 30);
            int space = middle.indexOf(' ');
            int lastSpace = middle.lastIndexOf(' ');
            if (space >= 0 && lastSpace > space) {
                at = full.indexOf(middle.substring(space + 1, lastSpace));
                if (at >= 0) return at;
            }
        }
        return -1;
    }

    private static Map<String, Double> reciprocalRankFusion(List<String> list1, List<String> list2, int k) {
        Map<String, Double> scores = new LinkedHashMap<>();
        for (int i = 0; i < list1.size(); i++) scores.merge(list1.get(i), 1.0 / (k + i + 1), Double::sum);
        for (int i = 0; i < list2.size(); i++) scores.merge(list2.get(i), 1.0 / (k + i + 1), Double::sum);
        return scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, LinkedHashMap::new));
    }

    private static String vectorToSolrString(float[] vector) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(vector[i]);
        }
        return sb.append("]").toString();
    }
}
