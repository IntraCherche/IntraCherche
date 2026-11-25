package eu.intracherche.intracherche.process;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * resources.indexingServicesNice: "0 = priorité normale (désactive l'abaissement)", and the
 * worker count a service is started with (embedding.workers, reranker.workers, …).
 */
class IndexingServicesNiceTest {

    @TempDir
    Path serviceDir;

    @Test
    void a_positive_niceness_starts_the_service_under_nice() {
        Assumptions.assumeTrue(Files.isExecutable(Path.of("/usr/bin/nice")), "needs /usr/bin/nice");

        List<String> cmd = new PythonServiceProcess("ocr", serviceDir, 8000, 3, Map.of(), 10, true).command();

        assertThat(cmd.subList(0, 3)).containsExactly("/usr/bin/nice", "-n", "10");
    }

    @Test
    void zero_starts_the_service_at_normal_priority() {
        List<String> cmd = new PythonServiceProcess("ocr", serviceDir, 8000, 3, Map.of(), 0, true).command();

        assertThat(cmd).doesNotContain("/usr/bin/nice");
    }

    @Test
    void the_service_gets_the_allocated_worker_count() {
        List<String> cmd = new PythonServiceProcess("embedding", serviceDir, 8002, 2, Map.of(), 0, true).command();

        assertThat(cmd).containsSequence("--workers", "2");
    }
}
