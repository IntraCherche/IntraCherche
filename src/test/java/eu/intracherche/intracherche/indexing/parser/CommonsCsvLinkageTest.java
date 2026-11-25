package eu.intracherche.intracherche.indexing.parser;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.junit.jupiter.api.Test;

import java.io.StringReader;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tika's TextAndCSVParser reads delimited text with commons-csv, whose reader extends
 * commons-io's UnsynchronizedBufferedReader (commons-io >= 2.17). With the 2.16.1 that
 * commons-compress pulls in, 3.0.0 failed a ficstat.txt on site with NoClassDefFoundError.
 * This pins the linkage rather than Tika's CSV sniffing, which decides per file.
 */
class CommonsCsvLinkageTest {

    @Test
    void commonsCsvLinksAgainstTheBundledCommonsIo() throws Exception {
        try (CSVParser parser = CSVParser.parse(new StringReader("fiches,1234\npersonnes,567\n"), CSVFormat.DEFAULT)) {
            assertThat(parser.getRecords()).hasSize(2);
        }
    }
}
