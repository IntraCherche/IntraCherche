package eu.intracherche.intracherche.indexing;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryHintsTest {

    private static final long MB = 1024 * 1024;

    @Test
    void theHeapIsDoubledWithinAQuarterOfTheRam() {
        // 31.4 GB server running on 3.9 GB: doubling would be 7.8 GB, a quarter of the RAM 7.85 GB.
        assertThat(MemoryHints.suggestedHeapMb(3995, 32154)).isEqualTo(7 * 1024);
        // 16 GB server on 2 GB: 4 GB, exactly the quarter.
        assertThat(MemoryHints.suggestedHeapMb(2048, 16384)).isEqualTo(4 * 1024);
        // Doubling would be 12 GB: capped by the quarter of 32 GB.
        assertThat(MemoryHints.suggestedHeapMb(6144, 32768)).isEqualTo(8 * 1024);
    }

    @Test
    void noAdviceToRaiseTheHeapWhenItIsAlreadyAsLargeAsTheServerShouldGive() {
        assertThat(MemoryHints.suggestedHeapMb(8192, 32768)).isEqualTo(-1);
        assertThat(MemoryHints.suggestedHeapMb(4096, 16384)).isEqualTo(-1);
        assertThat(MemoryHints.suggestedHeapMb(4096, -1)).isEqualTo(-1);
    }

    @Test
    void aSizeLimitIsOfferedOnlyForFormatsWhoseCostFollowsTheirSize() {
        // An 80 MB spreadsheet: 64 is the largest usual limit that leaves it out.
        assertThat(MemoryHints.sizeLimitExcludingMb("dir/vols.XLSX", 80 * MB)).isEqualTo(64);
        assertThat(MemoryHints.sizeLimitExcludingMb("dir/vols.ods", 200 * MB)).isEqualTo(128);
        // A scanned PDF costs by its pages, not by its size.
        assertThat(MemoryHints.sizeLimitExcludingMb("dir/scan.pdf", 80 * MB)).isEqualTo(-1);
        // Too small for a size limit to be an answer.
        assertThat(MemoryHints.sizeLimitExcludingMb("dir/small.xlsx", 5 * MB)).isEqualTo(-1);
        assertThat(MemoryHints.sizeLimitExcludingMb("dir/noextension", 80 * MB)).isEqualTo(-1);
    }

    @Test
    void theAdviceNamesTheSettingsToChange() {
        String advice = MemoryHints.advice("dir/vols.xlsx", 80 * MB, 3995, 32154);
        assertThat(advice).contains("7 Go").contains("IC_HEAP_MB=7168").contains("maxFileSizeMb: 64");

        String atMax = MemoryHints.advice("dir/scan.pdf", 80 * MB, 8192, 32768);
        assertThat(atMax).contains("déjà au maximum").doesNotContain("maxFileSizeMb");
    }
}
