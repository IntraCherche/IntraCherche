package eu.intracherche.intracherche.config;

import eu.intracherche.intracherche.alerts.AlertEmailService;
import eu.intracherche.intracherche.alerts.dao.NotificationDao;
import eu.intracherche.intracherche.alerts.dao.SubscriptionDao;
import eu.intracherche.intracherche.indexing.error.IndexingErrorDao;
import eu.intracherche.intracherche.indexing.worker.IndexingRoundDao;
import eu.intracherche.intracherche.indexing.outage.ServiceOutageMonitor;
import eu.intracherche.intracherche.indexing.worker.DocumentCompletionTracker;
import eu.intracherche.intracherche.indexing.worker.IndexWorkerManager;
import eu.intracherche.intracherche.indexing.worker.OcrWorkerManager;
import eu.intracherche.intracherche.sherlock.SherlockQueue;
import eu.intracherche.intracherche.security.RevokedSessions;
import eu.intracherche.intracherche.sherlock.SherlockQuestionDao;
import eu.intracherche.intracherche.sherlock.SherlockQuota;
import eu.intracherche.intracherche.sherlock.SherlockWorkerManager;
import eu.intracherche.intracherche.sherlock.LlmService;
import eu.intracherche.intracherche.queues.DiscoveryQueue;
import eu.intracherche.intracherche.queues.IndexTaskQueue;
import eu.intracherche.intracherche.queues.OcrQueue;
import eu.intracherche.intracherche.registry.FileRegistry;
import eu.intracherche.intracherche.services.IndexService;
import eu.intracherche.intracherche.services.OcrService;
import eu.intracherche.intracherche.security.SecurityService;
import eu.intracherche.intracherche.services.EmbeddingService;
import eu.intracherche.intracherche.services.RerankerService;
import eu.intracherche.intracherche.services.SolrCleanupService;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.impl.HttpJdkSolrClient;
import java.util.concurrent.TimeUnit;
import eu.intracherche.intracherche.security.IndexVisibilityResolver;
import eu.intracherche.intracherche.security.SecurityService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;


import java.nio.file.Path;
import java.sql.SQLException;

/**
 * THis class job is to take those raw strings
 * (like a URL or a file path) and turn them into
 * "living" objects (like a SolrClient or a loaded IndexesConfig)
 * that the app can actually use.
 */
@Configuration
public class AppBeanConfig {

    // Spring will automatically inject ApplicationConfig where needed

    private static final Logger log = LoggerFactory.getLogger(AppBeanConfig.class);

    // 1. Extract SolrConfig so it can be injected elsewhere
    @Bean
    public SolrConfig solrSettings(ApplicationConfig applicationConfig) {
        return applicationConfig.getSolrConfig();
    }

    // 2. Load the external YAML into a bean
    @Bean
    public IndexesConfig indexesConfig(ApplicationConfig applicationConfig, SecurityService securityService) {
        // Reading config file for the indexes from the Application config.
        // The loader resolves this against the filesystem first (e.g. an absolute
        // /etc/intracherche/indexes.yaml from a real install) and falls back to the
        // classpath for the bundled default used during development.
        String indexesConfigPath = applicationConfig.getIndexingConfig().indexesConfigPath();

        IndexesConfig indexes = IndexesConfigLoader.load(indexesConfigPath);
        // Here, so that no consumer ever holds an index whose groups were not checked against AD.
        IndexVisibilityResolver.resolve(indexes, securityService::findBySamAccountName);
        return indexes;
    }

    // 3. Create the shared SolrClient using the SolrConfig bean above.
    // Both Solr clients are HTTP/1.1 only: the JDK client otherwise tries an HTTP/2 upgrade, and Jetty
    // closes an idle connection after 2 minutes. The next write on it then fails with "Received
    // RST_STREAM: Stream cancelled" (or "header parser received no bytes"), and so does its retry.
    // A document that is embedded for tens of minutes before its first write hit this every time.
    @Bean
    @Primary
    public SolrClient solrClient(SolrConfig solrConfig) {
        return new HttpJdkSolrClient.Builder(solrConfig.solrBaseUrl())
                .useHttp1_1(true)
                .withConnectionTimeout(5000, TimeUnit.MILLISECONDS)
                .withIdleTimeout(10000, TimeUnit.MILLISECONDS)
                .build();
    }

    // Indexing only: a batch of pages can take Solr far longer than the 10 s that is right for a
    // search, and sharing the client would let a slow update stall /api/indices and every search.
    @Bean
    public SolrClient indexingSolrClient(SolrConfig solrConfig) {
        return new HttpJdkSolrClient.Builder(solrConfig.solrBaseUrl())
                .useHttp1_1(true)
                .withConnectionTimeout(5000, TimeUnit.MILLISECONDS)
                .withIdleTimeout(120_000, TimeUnit.MILLISECONDS)
                .build();
    }

    @Bean
    public FileRegistry fileRegistry(ApplicationConfig applicationConfig, IndexesConfig indexesConfig) throws SQLException {
        Path dbPath = Path.of(applicationConfig.getRegistryConfig().getDbPath());

        // Create the database file if it doesn't exist
        createDatabaseIfNotExists(dbPath);

        // This is created once and shared across the entire app
        return new FileRegistry(dbPath, indexesConfig);
    }

    @Bean
    public DiscoveryQueue discoveryQueue() {
        return new DiscoveryQueue(10_000);
    }

    @Bean
    public IndexTaskQueue indexTaskQueue() {
        return new IndexTaskQueue(100);
    }

    @Bean
    public IndexWorkerManager indexWorkerManager(@Qualifier("indexingSolrClient") SolrClient solrClient, IndexTaskQueue indexTaskQueue,
                                                 DocumentCompletionTracker completionTracker,
                                                 ApplicationConfig applicationConfig) {
        int commitWithinMs = applicationConfig.getIndexingConfig().commitWithinMs();
        IndexWorkerManager manager = new IndexWorkerManager(solrClient, indexTaskQueue, 4, commitWithinMs, completionTracker);
        manager.start();
        return manager;
    }

    @Bean
    public OcrQueue ocrQueue() {
        return new OcrQueue(100);
    }

    @Bean
    public OcrWorkerManager ocrWorkerManager(OcrService ocrService, EmbeddingService embeddingService,
                                             OcrQueue ocrQueue, IndexTaskQueue indexTaskQueue,
                                             OcrConfig ocrConfig, DocumentCompletionTracker completionTracker,
                                             ServiceOutageMonitor outageMonitor) {
        if (ocrConfig.usesNode()) {
            OcrService.Health node = ocrService.nodeHealth();
            if (node == null) {
                log.warn("The GPU node's OCR does not answer at {}: scanned pages will be read on this "
                        + "server's processor until it does.", ocrConfig.getGpuUrl());
            } else if (!node.onGpu()) {
                log.warn("The GPU node's OCR at {} runs on its processor ({}), not its GPU: set the GPU node up "
                        + "again on the node.", ocrConfig.getGpuUrl(), node.device());
            } else {
                log.info("Scanned pages are read on the GPU node's OCR at {}", ocrConfig.getGpuUrl());
            }
        }
        if (ocrService.isHealthy()) {
            log.info("OCR service is reachable at {}", ocrConfig.getPythonUrl());
        } else {
            log.warn("========================================================");
            log.warn("OCR service is NOT reachable at {}", ocrConfig.getPythonUrl());
            log.warn("Indexing of scanned PDF pages will wait until it is.");
            log.warn("========================================================");
        }
        OcrWorkerManager manager = new OcrWorkerManager(ocrService, embeddingService, ocrQueue, indexTaskQueue, ocrConfig, completionTracker, outageMonitor);
        manager.start();
        return manager;
    }

    /**
     * Creates an empty SQLite database file if it doesn't already exist
     */
    private static void createDatabaseIfNotExists(Path dbPath) {
        try {
            // Check if the file already exists
            if (!dbPath.toFile().exists()) {
                // Create parent directories if they don't exist
                dbPath.getParent().toFile().mkdirs();

                // Create empty database file by establishing a connection
                // This will create the file if it doesn't exist
                try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath())) {
                    // Connection is established, which creates the file if it doesn't exist
                    log.info("Created new database file at: " + dbPath.toAbsolutePath());
                }
            } else {
                log.info("Database file already exists at: " + dbPath.toAbsolutePath());
            }
        } catch (Exception e) {
            log.info("Error creating database file: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @Bean
    public SolrCleanupService solrCleanupService(SolrClient solrClient, FileRegistry fileRegistry) {
        return new SolrCleanupService(solrClient, fileRegistry);
    }

    @Bean
    public SubscriptionDao subscriptionDao(FileRegistry fileRegistry) {
        return new SubscriptionDao(fileRegistry.getConn());
    }

    @Bean
    public NotificationDao notificationDao(FileRegistry fileRegistry) {
        return new NotificationDao(fileRegistry.getConn());
    }

    @Bean
    public SherlockQuestionDao sherlockQuestionDao(FileRegistry fileRegistry) {
        return new SherlockQuestionDao(fileRegistry.getConn());
    }

    @Bean
    public SherlockQuota sherlockQuota(FileRegistry fileRegistry, LlmConfig llmConfig) {
        return new SherlockQuota(fileRegistry.getConn(), llmConfig);
    }

    @Bean
    public IndexingErrorDao indexingErrorDao(FileRegistry fileRegistry) {
        return new IndexingErrorDao(fileRegistry.getConn());
    }

    @Bean
    public RevokedSessions revokedSessions(FileRegistry fileRegistry) throws SQLException {
        return new RevokedSessions(fileRegistry.getConn());
    }

    @Bean
    public IndexingRoundDao indexingRoundDao(FileRegistry fileRegistry) {
        return new IndexingRoundDao(fileRegistry.getConn());
    }

    @Bean
    public SherlockQueue sherlockQueue(LlmConfig llmConfig) {
        return new SherlockQueue(llmConfig.getQueueCapacity());
    }

    @Bean
    public SherlockWorkerManager sherlockWorkerManager(LlmService llmService, LlmConfig llmConfig,
                                                        SherlockQueue sherlockQueue,
                                                        SherlockQuestionDao sherlockQuestionDao,
                                                        SherlockQuota sherlockQuota,
                                                        NotificationDao notificationDao,
                                                        AlertEmailService alertEmailService,
                                                        IndexesConfig indexesConfig, SolrClient solrClient,
                                                        SolrConfig solrConfig,
                                                        RerankerService rerankerService,
                                                        RerankerConfig rerankerConfig,
                                                        EmbeddingService embeddingService,
                                                        SecurityService securityService) {
        if (!llmConfig.isEnabled()) {
            log.info("Sherlock is disabled (llm.enabled=false) — the pane is hidden for every user");
        } else if (llmService.isHealthy()) {
            log.info("Ollama LLM service reachable — Sherlock is ready");
        } else {
            log.warn("========================================================");
            log.warn("Ollama LLM service is NOT reachable.");
            log.warn("Sherlock questions will be marked FAILED until");
            log.warn("Ollama is started and the model is pulled.");
            log.warn("========================================================");
        }
        SherlockWorkerManager manager = new SherlockWorkerManager(
                sherlockQueue, sherlockQuestionDao, sherlockQuota, llmService, llmConfig,
                solrClient, solrConfig, indexesConfig,
                notificationDao, alertEmailService,
                rerankerService, rerankerConfig, embeddingService, securityService);
        manager.start();
        return manager;
    }
}
