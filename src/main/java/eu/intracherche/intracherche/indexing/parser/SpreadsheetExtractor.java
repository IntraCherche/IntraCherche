package eu.intracherche.intracherche.indexing.parser;

import org.apache.poi.ss.usermodel.*;
import org.apache.tika.Tika;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.xml.sax.helpers.DefaultHandler;

import org.odftoolkit.odfdom.doc.OdfSpreadsheetDocument;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.*;

public class SpreadsheetExtractor {

    public static List<IndexedSection> extractSheets(byte[] data) throws Exception {

        Tika tika = new Tika();
        String mime = tika.detect(data);

        try (InputStream is = new ByteArrayInputStream(data)) {

            if (mime.equals("application/vnd.ms-excel") ||
                    mime.equals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")) {

                return extractWithPOI(is);

            } else if (mime.equals("application/vnd.oasis.opendocument.spreadsheet")) {

                return extractODS(data);

            } else {
                return Collections.emptyList();
            }
        }
    }

    private static final String TABLE_NS = "urn:oasis:names:tc:opendocument:xmlns:table:1.0";
    private static final String TEXT_NS = "urn:oasis:names:tc:opendocument:xmlns:text:1.0";

    /**
     * Reads each sheet straight from the table XML, row by row and cell by cell as they are
     * written. LibreOffice pads a formatted sheet with one empty cell repeated to the last
     * column and one empty row repeated to the last row; odfdom's OdfTable row/column API
     * expands those repeats (millions of cells) and runs the JVM out of heap. A repeated
     * cell or row that does hold text is read once — the repeat adds nothing to search.
     */
    private static List<IndexedSection> extractODS(byte[] data) throws Exception {

        List<IndexedSection> sections = new ArrayList<>();
        Set<String> seenNames = new HashSet<>();

        try (var is = new ByteArrayInputStream(data);
             OdfSpreadsheetDocument doc = OdfSpreadsheetDocument.loadDocument(is)) {

            NodeList tables = doc.getContentRoot().getElementsByTagNameNS(TABLE_NS, "table");
            int fallbackIndex = 1;

            for (int t = 0; t < tables.getLength(); t++) {
                Element table = (Element) tables.item(t);

                // --- Sheet name ---
                String name = table.getAttributeNS(TABLE_NS, "name");
                if (name.isBlank()) {
                    name = "Sheet " + fallbackIndex++;
                }
                name = uniqueName(name, seenNames);

                StringBuilder content = new StringBuilder();

                // Rows may sit inside header-rows / row-group wrappers, hence the descendant search.
                NodeList rows = table.getElementsByTagNameNS(TABLE_NS, "table-row");
                for (int r = 0; r < rows.getLength(); r++) {
                    boolean rowHasContent = false;

                    for (Node cell = rows.item(r).getFirstChild(); cell != null; cell = cell.getNextSibling()) {
                        if (!(cell instanceof Element e) || !TABLE_NS.equals(e.getNamespaceURI())) continue;

                        String text = cellText(e);
                        if (!text.isBlank()) {
                            content.append(text.trim()).append(" ");
                            rowHasContent = true;
                        }
                    }

                    if (rowHasContent) {
                        content.setLength(content.length() - 1);
                        content.append("\n");
                    }
                }

                String text = content.toString().trim();
                if (!text.isEmpty()) {
                    sections.add(new IndexedSection(name, text));
                }
            }
        }

        return sections;
    }

    /** The displayed text of a cell: its paragraphs, space-separated. */
    private static String cellText(Element cell) {
        NodeList paragraphs = cell.getElementsByTagNameNS(TEXT_NS, "p");
        StringJoiner text = new StringJoiner(" ");
        for (int i = 0; i < paragraphs.getLength(); i++) {
            String p = paragraphs.item(i).getTextContent();
            if (!p.isBlank()) text.add(p.trim());
        }
        return text.toString();
    }

    // ✅ Robust extraction for XLS/XLSX
    private static List<IndexedSection> extractWithPOI(InputStream is) throws Exception {

        List<IndexedSection> sections = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        Workbook workbook = WorkbookFactory.create(is);

        for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
            Sheet sheet = workbook.getSheetAt(i);

            String sheetName = uniqueName(sheet.getSheetName(), seen);

            StringBuilder content = new StringBuilder();

            for (Row row : sheet) {
                for (Cell cell : row) {
                    content.append(getCellValue(cell)).append(" ");
                }
                content.append("\n");
            }

            String text = content.toString().trim();
            if (!text.isEmpty()) {
                sections.add(new IndexedSection(sheetName, text));
            }
        }

        workbook.close();
        return sections;
    }

    private static String getCellValue(Cell cell) {
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> String.valueOf(cell.getNumericCellValue());
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> cell.getCellFormula();
            default -> "";
        };
    }



    private static class PlainTextHandler extends DefaultHandler {

        private final StringBuilder content = new StringBuilder();

        @Override
        public void characters(char[] ch, int start, int length) {
            content.append(ch, start, length);
        }

        public List<IndexedSection> getSections() {
            String text = content.toString().trim();
            if (text.isEmpty()) {
                return List.of();
            }
            return List.of(new IndexedSection("Sheet 1", text));
        }
    }

    private static String uniqueName(String base, Set<String> seen) {
        String name = base;
        int i = 1;
        while (seen.contains(name)) {
            name = base + " (" + i++ + ")";
        }
        seen.add(name);
        return name;
    }
}