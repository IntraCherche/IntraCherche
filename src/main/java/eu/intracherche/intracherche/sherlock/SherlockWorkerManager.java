package eu.intracherche.intracherche.sherlock;

import eu.intracherche.intracherche.alerts.AlertEmailService;
import eu.intracherche.intracherche.alerts.dao.NotificationDao;
import eu.intracherche.intracherche.config.IndexesConfig;
import eu.intracherche.intracherche.config.LlmConfig;
import eu.intracherche.intracherche.config.RerankerConfig;
import eu.intracherche.intracherche.config.SolrConfig;
import eu.intracherche.intracherche.security.SecurityService;
import eu.intracherche.intracherche.services.EmbeddingService;
import eu.intracherche.intracherche.services.RerankerService;
import jakarta.annotation.PreDestroy;
import org.apache.solr.client.solrj.SolrClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class SherlockWorkerManager {

    private static final Logger log = LoggerFactory.getLogger(SherlockWorkerManager.class);

    private final SherlockQueue queue;
    private final SherlockQuestionDao dao;
    private final SherlockQuota quota;
    private final LlmService llmService;
    private final LlmConfig llmConfig;
    private final SolrClient solrClient;
    private final SolrConfig solrConfig;
    private final IndexesConfig indexesConfig;
    private final NotificationDao notificationDao;
    private final AlertEmailService emailService;
    private final RerankerService rerankerService;
    private final RerankerConfig rerankerConfig;
    private final EmbeddingService embeddingService;
    private final SecurityService securityService;

    private ExecutorService executor;
    private SherlockWorker worker;
    private volatile boolean started = false;

    public SherlockWorkerManager(SherlockQueue queue, SherlockQuestionDao dao, SherlockQuota quota, LlmService llmService,
                                 LlmConfig llmConfig, SolrClient solrClient, SolrConfig solrConfig,
                                 IndexesConfig indexesConfig, NotificationDao notificationDao,
                                 AlertEmailService emailService,
                                 RerankerService rerankerService, RerankerConfig rerankerConfig,
                                 EmbeddingService embeddingService, SecurityService securityService) {
        this.queue = queue;
        this.dao = dao;
        this.quota = quota;
        this.llmService = llmService;
        this.llmConfig = llmConfig;
        this.solrClient = solrClient;
        this.solrConfig = solrConfig;
        this.indexesConfig = indexesConfig;
        this.notificationDao = notificationDao;
        this.emailService = emailService;
        this.rerankerService = rerankerService;
        this.rerankerConfig = rerankerConfig;
        this.embeddingService = embeddingService;
        this.securityService = securityService;
    }

    public void start() {
        if (started) return;
        started = true;

        long ts = System.currentTimeMillis();
        executor = Executors.newFixedThreadPool(1, r -> {
            Thread t = new Thread(r, "SherlockWorker-" + ts);
            t.setDaemon(true);
            return t;
        });

        SherlockRetriever retriever = new SherlockRetriever(solrClient, solrConfig, indexesConfig, llmConfig,
                rerankerService, embeddingService);
        worker = new SherlockWorker(queue, dao, quota, llmService, llmConfig, retriever, notificationDao, emailService,
                rerankerService, rerankerConfig, securityService);
        executor.submit(worker);
        log.info("SherlockWorkerManager started (single CPU-bound worker thread)");
    }

    @PreDestroy
    public void preDestroy() {
        stop(30);
    }

    public void stop(int timeoutSeconds) {
        if (executor == null) return;
        if (worker != null) worker.stop();
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(timeoutSeconds, TimeUnit.SECONDS)) {
                log.warn("SherlockWorker did not terminate within {}s", timeoutSeconds);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
