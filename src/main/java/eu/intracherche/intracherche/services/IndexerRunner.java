package eu.intracherche.intracherche.services;

import eu.intracherche.intracherche.app.ApplicationOrchestrator;
import eu.intracherche.intracherche.process.PythonServiceManager;
import eu.intracherche.intracherche.process.SolrProcessManager;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class IndexerRunner implements CommandLineRunner {

    private final ApplicationOrchestrator orchestrator;
    private final SolrProcessManager solrProcessManager;
    private final PythonServiceManager pythonServiceManager;

    public IndexerRunner(ApplicationOrchestrator orchestrator,
                         SolrProcessManager solrProcessManager,
                         PythonServiceManager pythonServiceManager) {
        this.orchestrator = orchestrator;
        this.solrProcessManager = solrProcessManager;
        this.pythonServiceManager = pythonServiceManager;
    }

    @Override
    public void run(String... args) {
        solrProcessManager.waitUntilReady(Duration.ofSeconds(120));
        pythonServiceManager.waitUntilAllReady(Duration.ofSeconds(300));
        orchestrator.runIndexing();
    }
}
