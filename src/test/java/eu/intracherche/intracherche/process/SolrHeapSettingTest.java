package eu.intracherche.intracherche.process;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The RAM budget must count the heap Solr is really started with, which the installer sets in solr.in.sh. */
class SolrHeapSettingTest {

    @TempDir
    Path dir;

    private long heapOf(String content) throws Exception {
        Path file = dir.resolve("solr.in.sh");
        Files.writeString(file, content);
        return ResourceValidator.solrHeapMb(file);
    }

    @Test
    void the_bundled_default_counts_when_nothing_is_set() throws Exception {
        assertEquals(512, heapOf("#SOLR_HEAP=\"512m\"\n#SOLR_JAVA_MEM=\"-Xms512m -Xmx512m\"\n"));
    }

    @Test
    void a_missing_file_counts_as_the_default() {
        assertEquals(512, ResourceValidator.solrHeapMb(dir.resolve("absent.sh")));
    }

    @Test
    void the_installer_block_is_read_in_megabytes_or_gigabytes() throws Exception {
        assertEquals(1024, heapOf("#SOLR_HEAP=\"512m\"\n# BEGIN IntraCherche heap\nSOLR_HEAP=\"1024m\"\n# END IntraCherche heap\n"));
        assertEquals(2048, heapOf("SOLR_HEAP=\"2g\"\n"));
    }

    @Test
    void solr_heap_wins_over_solr_java_mem_as_it_does_in_solr() throws Exception {
        assertEquals(1536, heapOf("SOLR_JAVA_MEM=\"-Xms512m -Xmx3g\"\nSOLR_HEAP=\"1536m\"\n"));
        assertEquals(3072, heapOf("SOLR_JAVA_MEM=\"-Xms512m -Xmx3g\"\n"));
    }
}
