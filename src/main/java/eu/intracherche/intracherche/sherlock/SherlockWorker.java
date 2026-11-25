package eu.intracherche.intracherche.sherlock;

import eu.intracherche.intracherche.alerts.AlertEmailService;
import eu.intracherche.intracherche.alerts.dao.NotificationDao;
import eu.intracherche.intracherche.alerts.model.AppNotification;
import eu.intracherche.intracherche.config.LlmConfig;
import eu.intracherche.intracherche.config.RerankerConfig;
import eu.intracherche.intracherche.security.SecurityService;
import eu.intracherche.intracherche.services.RerankerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class SherlockWorker implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(SherlockWorker.class);

    private volatile boolean running = true;

    private final SherlockQueue queue;
    private final SherlockQuestionDao dao;
    private final SherlockQuota quota;
    private final LlmService llmService;
    private final LlmConfig llmConfig;
    private final SherlockRetriever retriever;
    private final NotificationDao notificationDao;
    private final AlertEmailService emailService;
    private final RerankerService rerankerService;
    private final RerankerConfig rerankerConfig;
    private final SecurityService securityService;

    public SherlockWorker(SherlockQueue queue, SherlockQuestionDao dao, SherlockQuota quota, LlmService llmService,
                          LlmConfig llmConfig, SherlockRetriever retriever, NotificationDao notificationDao,
                          AlertEmailService emailService,
                          RerankerService rerankerService, RerankerConfig rerankerConfig,
                          SecurityService securityService) {
        this.queue = queue;
        this.dao = dao;
        this.quota = quota;
        this.llmService = llmService;
        this.llmConfig = llmConfig;
        this.retriever = retriever;
        this.notificationDao = notificationDao;
        this.emailService = emailService;
        this.securityService = securityService;
        this.rerankerService = rerankerService;
        this.rerankerConfig = rerankerConfig;
    }

    @Override
    public void run() {
        log.info("SherlockWorker started");
        while (running) {
            try {
                SherlockQuestion question = queue.take();
                processQuestion(question);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Unexpected error in SherlockWorker loop", e);
            }
        }
        log.info("SherlockWorker stopped");
    }

    public void stop() {
        running = false;
    }

    private void processQuestion(SherlockQuestion q) {
        log.info("Processing Sherlock question {} for user {}", q.id(), q.username());
        try {
            dao.updateStatusProcessing(q.id());

            // Re-fetch the user's SIDs rather than trust q.userSids(): that field is a snapshot
            // from when the question was asked, and processing can lag behind the queue by a
            // while. Retrieval must reflect what the user can read NOW — access revoked between
            // asking and processing must take effect immediately, not on the next question, and
            // an answer built from the old permissions would otherwise sit readable in the
            // asker's own history for good. getEffectiveSids() is TTL-cached, so this costs a
            // real LDAP round-trip only once per cache window, same as a live search.
            List<String> userSids = securityService.getEffectiveSids(q.username());

            // Phase 1: the earlier turn, if any. Read before retrieval, not after: a follow-up
            // says what it refers to only in the turn before it, and retrieval needs that as
            // much as synthesis does.
            List<ConversationTurn> history = new ArrayList<>();
            if (q.parentId() != null) {
                SherlockQuestion parent = dao.findById(q.parentId());
                if (parent != null && parent.answer() != null) {
                    history.add(new ConversationTurn(parent.question(), parent.answer()));
                }
            }

            // Phase 2: correct the words no readable page carries, as "Vouliez-vous dire…" would
            Map<String, String> spelling = retriever.spellingCorrections(q.question(), userSids, q.indices());
            String question = SherlockRetriever.applyCorrections(q.question(), spelling);
            if (!spelling.isEmpty()) {
                log.info("Question {} corrected before searching: {}", q.id(), spelling);
                dao.updateSpelling(q.id(), spelling);
            }

            // Phase 3: expand the question into keywords, and restate it so it stands alone
            LlmService.ExpandedQuery expanded = llmService.expandQuery(question, history);
            List<String> keywords = expanded.keywords();
            String searchQuestion = expanded.searchQuestion();
            log.debug("Expanded keywords for {}: {}", q.id(), keywords);
            if (!searchQuestion.equals(question)) {
                log.info("Follow-up {} searched as: {}", q.id(), searchQuestion);
            }

            // Phase 4: retrieve candidates, rerank them against the question, select
            SherlockRetriever.Settings settings =
                    SherlockRetriever.Settings.from(llmConfig, rerankerConfig, rerankerService.isEnabled(),
                            rerankerService.isEnabled() && rerankerService.onGpu());
            if (q.documentId() != null) {
                // Before the search, not after: reading a long document is the slow part.
                SherlockRetriever.DocumentPlan plan = retriever.planDocument(q.documentId(), userSids, q.indices(), settings);
                String notice = notice(plan, settings);
                if (notice != null) dao.updateNotice(q.id(), notice);
                if (plan.reading() == SherlockRetriever.DocumentReading.IN_BATCHES && !settings.onGpu()) {
                    log.warn("Question {} reads all {} pages of {} ({} windows) on the CPU, in batches of {}: expect several "
                            + "minutes. A reranker on a GPU scores them in seconds.",
                            q.id(), plan.pages(), q.documentName(), plan.windows(), settings.candidates());
                } else if (plan.reading() == SherlockRetriever.DocumentReading.PARTIAL) {
                    log.warn("Question {}: {} has {} pages, too many to read whole{}; only the pages a search inside it "
                            + "finds are read", q.id(), q.documentName(), plan.pages(),
                            settings.onGpu() ? "" : " on the CPU (a reranker on a GPU reads five times more)");
                }
            }
            SherlockRetriever.Retrieval retrieval =
                    retriever.retrieve(searchQuestion, keywords, userSids, q.indices(), q.documentId(), settings);
            List<SherlockResult> snippets = retrieval.selected();
            log.info("Retrieved {} candidate snippets for question {}, {} selected",
                    retrieval.candidates().size(), q.id(), snippets.size());
            if (log.isDebugEnabled()) log.debug("Candidates for question {}:\n{}", q.id(), describe(retrieval));

            // Phase 5: synthesize the answer from the user's own words, not the restatement
            log.info("Starting LLM synthesis for question {} ({} snippets, {} prior turns)", q.id(), snippets.size(), history.size());
            long t0 = System.currentTimeMillis();
            String answer = llmService.synthesizeAnswer(question, snippets, history, keywords);
            log.info("LLM synthesis completed for question {} in {}s", q.id(), (System.currentTimeMillis() - t0) / 1000);

            List<SherlockCitation> citations = snippets.stream()
                    .map(s -> new SherlockCitation(s.id(), s.path(), s.filename(), s.page()))
                    .collect(Collectors.toList());
            List<SherlockQuote> quotes = QuoteVerifier.verify(answer, snippets);
            long unverified = quotes.stream().filter(qt -> !qt.verified()).count();
            if (unverified > 0) {
                log.warn("Question {}: {} of {} quotation(s) are not in the passage they are cited from", q.id(), unverified, quotes.size());
            } else {
                log.info("Question {}: {} quotation(s), all found in their passage", q.id(), quotes.size());
            }
            dao.updateAnswered(q.id(), answer, citations, quotes, System.currentTimeMillis());
            log.info("Sherlock question {} answered successfully", q.id());

            try {
                String excerpt = q.question().length() > 80 ? q.question().substring(0, 77) + "…" : q.question();
                String message = "Sherlock a répondu à votre question : « " + excerpt + " »";
                notificationDao.save(AppNotification.forSherlockAnswer(q.username(), q.id(), message, System.currentTimeMillis()));
                notificationDao.pruneOld(q.username(), 100);

                String answerExcerpt = answer.length() > 500 ? answer.substring(0, 497) + "…" : answer;
                emailService.sendSherlockAnswerEmail(q.userEmail(), q.username(), q.question(), answerExcerpt);
            } catch (Exception e) {
                log.warn("Post-answer notification/email failed for question {} (answer already saved): {}", q.id(), e.getMessage());
            }

        } catch (Exception e) {
            log.error("Failed to process Sherlock question {}: {}", q.id(), e.getMessage(), e);
            try {
                dao.updateFailed(q.id());
            } catch (Exception ex) {
                log.error("Could not mark question {} as FAILED", q.id(), ex);
            }
            // The failure is the server's: the user gets the question back.
            quota.refund(q.username(), q.createdAt());
        }
    }

    /**
     * What the user is told about how a question on one document reads it, or null when it is
     * read whole at the usual speed. Reading in batches gives the same answer, so on a GPU,
     * where it takes seconds, there is nothing to say.
     */
    static String notice(SherlockRetriever.DocumentPlan plan, SherlockRetriever.Settings settings) {
        return switch (plan.reading()) {
            case WHOLE -> null;
            case IN_BATCHES -> settings.onGpu() ? null
                    : "Document long (" + plan.pages() + " pages) lu en entier sans carte graphique : "
                    + "la réponse peut prendre plusieurs minutes.";
            case PARTIAL -> "Document trop long pour être lu en entier (" + plan.pages() + " pages) : seules les pages "
                    + "les plus proches de la question ont été lues, la réponse peut être incomplète.";
        };
    }

    /**
     * One line per candidate, best first: whether it reached the LLM, its reranker score, its
     * position after retrieval, and where it comes from. When an answer misses a page that holds
     * it, this shows whether retrieval never found the page or the reranker pushed it down.
     */
    static String describe(SherlockRetriever.Retrieval retrieval) {
        Set<String> selected = new HashSet<>();
        retrieval.selected().forEach(s -> selected.add(s.id()));
        StringBuilder sb = new StringBuilder();
        for (SherlockRetriever.Candidate c : retrieval.candidates()) {
            SherlockResult r = c.result();
            sb.append(selected.contains(r.id()) ? "  * " : "    ")
                    .append(c.rerankScore() == null ? "     -   " : String.format("%8.5f ", c.rerankScore()))
                    .append(String.format("ret#%-3d ", c.retrievalRank()))
                    .append(r.filename())
                    .append(r.page() != null ? ", p" + r.page() : "")
                    .append('\n');
        }
        return sb.toString().stripTrailing();
    }
}
