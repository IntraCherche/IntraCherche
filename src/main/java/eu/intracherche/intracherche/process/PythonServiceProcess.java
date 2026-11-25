package eu.intracherche.intracherche.process;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class PythonServiceProcess {
    private static final Logger log = LoggerFactory.getLogger(PythonServiceProcess.class);

    // Resolve uv once at class-load time. ProcessBuilder uses the parent process's
    // PATH (via execvp) to find executables — setting PATH in the child environment
    // does not help. Using the full path is unambiguous regardless of launch context.
    private static final String UV = findUv();

    private static String findUv() {
        String home = System.getProperty("user.home");
        for (String candidate : List.of(
                // Bundled uv shipped under the install dir (./uv/bin/uv relative to the
                // working directory, e.g. /opt/intracherche/uv/bin/uv). Checked first so a
                // packaged install is self-contained and independent of PATH/HOME, which
                // systemd does not populate.
                Path.of("uv", "bin", "uv").toAbsolutePath().toString(),
                home + "/.local/bin/uv",
                home + "/.cargo/bin/uv",
                "/usr/local/bin/uv",
                "/usr/bin/uv"
        )) {
            if (Files.exists(Path.of(candidate))) {
                log.info("uv found at {}", candidate);
                return candidate;
            }
        }
        log.warn("uv not found in common locations — falling back to 'uv' on PATH");
        return "uv";
    }

    private final String name;
    private final Path serviceDir;
    private final int port;
    private final int workers;
    private final Map<String, String> envVars;
    private final int nice;
    private final boolean packaged;

    private static final Path NICE = Path.of("/usr/bin/nice");

    private volatile Process process;

    /**
     * @param nice scheduling niceness to start the service with (0 = normal priority). Ignored
     *             where {@code /usr/bin/nice} does not exist.
     * @param packaged true for an installed service whose venv the installer built from the
     *             bundled wheels: it is then run as-is ({@code uv run --no-sync}). A plain
     *             {@code uv run} first syncs the venv against uv.lock, which records the spaCy
     *             models as GitHub URLs — the locally installed wheels never match that, so uv
     *             re-downloads them and the service fails to start on an offline host.
     */
    public PythonServiceProcess(String name, Path serviceDir, int port, int workers,
                                Map<String, String> envVars, int nice, boolean packaged) {
        this.name = name;
        this.serviceDir = serviceDir;
        this.port = port;
        this.workers = workers;
        this.envVars = envVars;
        this.nice = nice;
        this.packaged = packaged;
    }

    /** The command line: resources.indexingServicesNice and the worker count are applied here. */
    List<String> command() {
        List<String> serve = new ArrayList<>(packaged ? List.of(UV, "run", "--no-sync") : List.of(UV, "run"));
        // Use gunicorn only if it is declared as a dependency; otherwise use uvicorn directly.
        serve.addAll(hasGunicorn()
            ? List.of("gunicorn", "app:app",
                "--workers", String.valueOf(workers),
                "--worker-class", "uvicorn.workers.UvicornWorker",
                "--bind", "127.0.0.1:" + port)
            : List.of("uvicorn", "app:app",
                "--host", "127.0.0.1",
                "--port", String.valueOf(port),
                "--workers", String.valueOf(workers)));
        // Workers are forked by gunicorn/uvicorn and inherit the niceness.
        List<String> cmd = new ArrayList<>();
        boolean niced = nice > 0 && Files.isExecutable(NICE);
        if (niced) cmd.addAll(List.of(NICE.toString(), "-n", String.valueOf(nice)));
        cmd.addAll(serve);
        return cmd;
    }

    public synchronized void start() {
        List<String> cmd = command();
        boolean niced = cmd.get(0).equals(NICE.toString());
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(serviceDir.toFile());
            pb.environment().putAll(envVars);
            pb.redirectErrorStream(true);
            process = pb.start();
            Thread reader = new Thread(() -> {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        log.info("[{}] {}", name, line);
                    }
                } catch (Exception ignored) {}
            });
            reader.setDaemon(true);
            reader.setName("log-" + name);
            reader.start();
            log.info("Started {} on port {} ({} workers, pid {}{})", name, port, workers, process.pid(),
                    niced ? ", nice " + nice : "");
        } catch (Exception e) {
            log.error("Failed to start {}: {}", name, e.getMessage(), e);
        }
    }

    public synchronized void stop() {
        if (process == null || !process.isAlive()) return;
        log.info("Stopping {}...", name);
        process.destroy();
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                log.warn("{} did not exit gracefully, force-killing", name);
                process.destroyForcibly();
                process.waitFor(3, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
        log.info("{} stopped", name);
    }

    public boolean isAlive() {
        return process != null && process.isAlive();
    }

    public String getName() { return name; }
    public int getPort()    { return port; }

    private boolean hasGunicorn() {
        try {
            return Files.readString(serviceDir.resolve("pyproject.toml")).contains("gunicorn");
        } catch (Exception e) {
            return false;
        }
    }
}
