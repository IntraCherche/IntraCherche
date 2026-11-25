package eu.intracherche.intracherche.distribution;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The systemd unit written by install.sh must make the JVM exit on an OutOfMemoryError and
 * let systemd restart it. Without the flag a JVM that runs out of heap on one thread stays
 * alive with a dead HTTP client: the service reads "active" while every file fails.
 */
class ServiceUnitTest {

    private static String serviceUnit() throws IOException {
        String script = Files.readString(Path.of("distribution", "install.sh"));
        int start = script.indexOf("cat > /etc/systemd/system/intracherche.service <<UNIT");
        assertTrue(start >= 0, "the intracherche.service heredoc is no longer in install.sh");
        int end = script.indexOf("\nUNIT\n", start);
        assertTrue(end > start, "the intracherche.service heredoc has no terminator");
        return script.substring(start, end);
    }

    @Test
    void theJvmExitsOnOutOfMemoryError() throws IOException {
        String execStart = serviceUnit().lines()
                .dropWhile(l -> !l.startsWith("ExecStart="))
                .takeWhile(l -> !l.startsWith("Restart="))
                .reduce("", (a, b) -> a + b + "\n");
        assertTrue(execStart.contains("-XX:+ExitOnOutOfMemoryError"),
                "ExecStart must carry -XX:+ExitOnOutOfMemoryError:\n" + execStart);
        assertTrue(execStart.contains("-jar"), "the flag must come before -jar, as a JVM option:\n" + execStart);
        assertTrue(execStart.indexOf("-XX:+ExitOnOutOfMemoryError") < execStart.indexOf("-jar"),
                "a JVM option placed after -jar would be passed to the application instead:\n" + execStart);
    }

    @Test
    void systemdRestartsTheServiceAfterThatExit() throws IOException {
        assertTrue(serviceUnit().lines().anyMatch(l -> l.equals("Restart=on-failure")),
                "Restart=on-failure is what brings the service back after the OOM exit");
    }
}
