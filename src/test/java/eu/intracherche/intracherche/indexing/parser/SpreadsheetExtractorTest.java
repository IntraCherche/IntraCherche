package eu.intracherche.intracherche.indexing.parser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for the ODS path of {@link SpreadsheetExtractor}.
 */
class SpreadsheetExtractorTest {

    /**
     * LibreOffice saves a formatted sheet as a few real cells followed by one empty cell
     * repeated to the last column and one empty row repeated to the last row. Expanding
     * those repeats (as odfdom's OdfTable row/column API does) ran a 3.0.0 install out of
     * heap on a real "Catalogue des profils" spreadsheet.
     */
    @Test
    @Timeout(20)
    void odsWithHugeRepeatedEmptyRowsAndColumnsIsReadWithoutExpandingThem() throws Exception {
        String rows = """
                <table:table-row>
                  <table:table-cell office:value-type="string"><text:p>Profil</text:p></table:table-cell>
                  <table:table-cell office:value-type="string"><text:p>Droits</text:p></table:table-cell>
                  <table:table-cell table:number-columns-repeated="16382"/>
                </table:table-row>
                <table:table-row>
                  <table:table-cell office:value-type="string"><text:p>Administrateur</text:p></table:table-cell>
                  <table:table-cell office:value-type="string"><text:p>Lecture</text:p><text:p>Ecriture</text:p></table:table-cell>
                  <table:table-cell table:number-columns-repeated="16382"/>
                </table:table-row>
                <table:table-row table:number-rows-repeated="1048574">
                  <table:table-cell table:number-columns-repeated="16384"/>
                </table:table-row>
                """;

        List<IndexedSection> sections = SpreadsheetExtractor.extractSheets(ods("Profils", rows));

        assertThat(sections).hasSize(1);
        assertThat(sections.getFirst().label()).isEqualTo("Profils");
        assertThat(sections.getFirst().content())
                .isEqualTo("Profil Droits\nAdministrateur Lecture Ecriture");
    }

    @Test
    void odsReadsEverySheetAndSkipsEmptyOnes() throws Exception {
        String tables = table("Agents", """
                <table:table-row>
                  <table:table-cell office:value-type="string"><text:p>Dupont</text:p></table:table-cell>
                  <table:table-cell office:value-type="float" office:value="42"><text:p>42</text:p></table:table-cell>
                </table:table-row>
                """)
                + table("Vide", "<table:table-row><table:table-cell/></table:table-row>")
                + table("Sites", """
                <table:table-row>
                  <table:covered-table-cell/>
                  <table:table-cell office:value-type="string"><text:p>Versailles</text:p></table:table-cell>
                </table:table-row>
                """);

        List<IndexedSection> sections = SpreadsheetExtractor.extractSheets(odsWithTables(tables));

        assertThat(sections).extracting(IndexedSection::label).containsExactly("Agents", "Sites");
        assertThat(sections).extracting(IndexedSection::content).containsExactly("Dupont 42", "Versailles");
    }

    private static String table(String name, String rows) {
        return "<table:table table:name=\"" + name + "\">" + rows + "</table:table>";
    }

    private static byte[] ods(String sheetName, String rows) throws Exception {
        return odsWithTables(table(sheetName, rows));
    }

    private static byte[] odsWithTables(String tables) throws Exception {
        String content = """
                <?xml version="1.0" encoding="UTF-8"?>
                <office:document-content
                    xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0"
                    xmlns:table="urn:oasis:names:tc:opendocument:xmlns:table:1.0"
                    xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0"
                    office:version="1.2">
                  <office:body><office:spreadsheet>%s</office:spreadsheet></office:body>
                </office:document-content>
                """.formatted(tables).strip();
        String manifest = """
                <?xml version="1.0" encoding="UTF-8"?>
                <manifest:manifest xmlns:manifest="urn:oasis:names:tc:opendocument:xmlns:manifest:1.0" manifest:version="1.2">
                  <manifest:file-entry manifest:full-path="/" manifest:media-type="application/vnd.oasis.opendocument.spreadsheet"/>
                  <manifest:file-entry manifest:full-path="content.xml" manifest:media-type="text/xml"/>
                </manifest:manifest>
                """.strip();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            // The mimetype entry comes first and uncompressed, which is how Tika recognises ODS.
            byte[] mimetype = "application/vnd.oasis.opendocument.spreadsheet".getBytes(StandardCharsets.US_ASCII);
            ZipEntry entry = new ZipEntry("mimetype");
            entry.setMethod(ZipEntry.STORED);
            entry.setSize(mimetype.length);
            CRC32 crc = new CRC32();
            crc.update(mimetype);
            entry.setCrc(crc.getValue());
            zip.putNextEntry(entry);
            zip.write(mimetype);
            zip.closeEntry();

            zip.putNextEntry(new ZipEntry("content.xml"));
            zip.write(content.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();

            zip.putNextEntry(new ZipEntry("META-INF/manifest.xml"));
            zip.write(manifest.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return out.toByteArray();
    }
}
