package eu.intracherche.intracherche.process;

import eu.intracherche.intracherche.config.SolrConfig;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.request.CoreAdminRequest;
import org.apache.solr.common.params.CoreAdminParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
public class SolrProcessManager {
    private static final Logger log = LoggerFactory.getLogger(SolrProcessManager.class);
    // Solr is expected at ./solr/ relative to the working directory
    private static final Path SOLR_DIR = Path.of("solr");

    private final SolrConfig solrConfig;
    private final SolrClient solrClient;

    private volatile boolean restarting = false;

    public SolrProcessManager(SolrConfig solrConfig, SolrClient solrClient) {
        this.solrConfig = solrConfig;
        this.solrClient = solrClient;
    }

    @PostConstruct
    public void start() {
        if (!isManaged()) return;
        Thread.ofVirtual().name("start-solr").start(this::doStart);
    }

    @PreDestroy
    public void stop() {
        if (!isManaged()) return;
        log.info("Stopping Solr...");
        runScript("stop", "-p", String.valueOf(solrPort()));
    }

    public void restart() {
        if (!isManaged()) throw new IllegalStateException("Solr is not managed by IntraCherche (solr/ directory not found)");
        restarting = true;
        Thread.ofVirtual().name("restart-solr").start(() -> {
            try {
                doStart();
            } finally {
                restarting = false;
            }
        });
    }

    public boolean isManaged() {
        return Files.exists(SOLR_DIR.resolve("bin").resolve("solr"));
    }

    public boolean isRestarting() { return restarting; }

    public void waitUntilReady(Duration timeout) {
        if (!isManaged()) return;
        Instant deadline = Instant.now().plus(timeout);
        log.info("Waiting for Solr to become ready (timeout {}s)...", timeout.getSeconds());
        while (Instant.now().isBefore(deadline)) {
            if (solrResponds()) {
                log.info("Solr is ready");
                return;
            }
            try { Thread.sleep(3_000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            log.info("Waiting for Solr... ({} s remaining)",
                Duration.between(Instant.now(), deadline).getSeconds());
        }
        log.warn("Solr did not become ready within {}s — continuing anyway", timeout.getSeconds());
    }

    private void doStart() {
        log.info("Starting Solr from {}...", SOLR_DIR.toAbsolutePath());
        String baseUrl = solrConfig.solrBaseUrl();
        String allow   = baseUrl + "," + baseUrl.replace("localhost", "127.0.0.1");
        // Use "restart" so the command is idempotent: starts Solr whether or not it was running
        int exitCode = runScript("restart",
            "-Dsolr.allowUrls=" + allow,
            "-Dsolr.config.lib.enabled=true");
        if (exitCode == 0) {
            log.info("Solr restart script completed");
        } else {
            log.warn("Solr restart script exited with code {}", exitCode);
        }
    }

    private int runScript(String... args) {
        Path script = SOLR_DIR.resolve("bin").resolve("solr");
        List<String> cmd = new ArrayList<>();
        cmd.add(script.toString());
        cmd.addAll(List.of(args));
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            // Solr's bin/solr launcher invokes `java` via SOLR_JAVA_HOME / JAVA_HOME / PATH.
            // systemd starts our JVM by absolute path without exporting either, so the
            // subprocess would otherwise find no java. Hand Solr the very JVM running this
            // app: it is guaranteed present and version-compatible, regardless of whether
            // Java is bundled, apt-installed, or custom.
            pb.environment().put("JAVA_HOME", System.getProperty("java.home"));
            pb.inheritIO();
            Process p = pb.start();
            boolean finished = p.waitFor(60, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                log.warn("Solr script timed out");
                return -1;
            }
            return p.exitValue();
        } catch (Exception e) {
            log.error("Error running Solr script: {}", e.getMessage(), e);
            return -1;
        }
    }

    private boolean solrResponds() {
        try {
            CoreAdminRequest req = new CoreAdminRequest();
            req.setAction(CoreAdminParams.CoreAdminAction.STATUS);
            req.process(solrClient);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private int solrPort() {
        return URI.create(solrConfig.solrBaseUrl()).getPort();
    }
}
