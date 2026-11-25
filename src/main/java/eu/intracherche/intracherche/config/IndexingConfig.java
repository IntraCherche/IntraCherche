package eu.intracherche.intracherche.config;


import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;

/**
 * Bound by Spring from {@code indexing-config}: component names are the YAML keys, so a key only
 * takes effect if a component carries exactly its name. Durations are kept in the unit the admin
 * writes; {@link #cycleIntervalMs()} converts it.
 *
 * @param cycleIntervalMinutes pause between two indexing cycles
 * @param maxFileSizeMb a larger file is not read at all — it is read into memory whole — and is
 *                      indexed by its name only
 * @param maxTextChars  text kept per file; past it, the rest of the file is not indexed
 */
public record IndexingConfig(
    String indexesConfigPath,
    long cycleIntervalMinutes,
    long maxFileSizeMb,
    long maxTextChars
) {
    public static final long DEFAULT_MAX_FILE_SIZE_MB = 256;
    public static final long DEFAULT_MAX_TEXT_CHARS = 50_000_000;

    public IndexingConfig {
        if (indexesConfigPath == null || indexesConfigPath.isBlank()) {
            throw new IllegalArgumentException("indexesConfigPath must not be empty");
        }
        if (cycleIntervalMinutes <= 0) cycleIntervalMinutes = 1;   // default: 1 minute
        if (maxFileSizeMb <= 0) maxFileSizeMb = DEFAULT_MAX_FILE_SIZE_MB;
        if (maxTextChars <= 0) maxTextChars = DEFAULT_MAX_TEXT_CHARS;
    }

    public long cycleIntervalMs() {
        return cycleIntervalMinutes * 60_000L;
    }

    /**
     * How soon Solr makes newly indexed documents searchable. Fixed: sooner costs Solr more merging
     * during bulk indexing, later delays new documents, and 30 s suits every deployment — no admin
     * setting (commitWithinSeconds was documented, never took effect, and nobody missed it).
     */
    public int commitWithinMs() {
        return 30_000;
    }

    public long maxFileSizeBytes() {
        return maxFileSizeMb * 1024L * 1024L;
    }

}
