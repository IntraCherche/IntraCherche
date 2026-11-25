package eu.intracherche.intracherche.services;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A paused index must stop growing: files discovered before the pause stay pending instead of
 * being indexed by the loop that runs for every other, active index (Sept. 2026).
 */
class FileIndexingPausedIndexTest {

    private static final Set<String> PAUSED = Set.of("Index_Wikipedia_20k");

    @Test
    void a_file_of_a_paused_index_waits() {
        assertThat(FileIndexingService.onlyInPausedCores(List.of("Index_Wikipedia_20k"), PAUSED)).isTrue();
    }

    @Test
    void a_file_of_an_active_index_is_indexed() {
        assertThat(FileIndexingService.onlyInPausedCores(List.of("Index_Demo_OCR"), PAUSED)).isFalse();
        assertThat(FileIndexingService.onlyInPausedCores(List.of("Index_Demo_OCR"), Set.of())).isFalse();
    }

    @Test
    void a_file_shared_with_an_active_index_is_not_held_back() {
        assertThat(FileIndexingService.onlyInPausedCores(List.of("Index_Wikipedia_20k", "Index_Demo_OCR"), PAUSED))
                .isFalse();
    }

    @Test
    void a_file_with_no_index_is_left_to_the_existing_handling() {
        assertThat(FileIndexingService.onlyInPausedCores(List.of(), PAUSED)).isFalse();
        assertThat(FileIndexingService.onlyInPausedCores(null, PAUSED)).isFalse();
    }
}
