package eu.intracherche.intracherche.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for {@link MimeTypeUtils#categorize(String)}: the families feeding the
 * type icon on each search result line, checked against the MIME types Tika actually emits.
 */
class MimeTypeUtilsCategorizeTest {

    @ParameterizedTest
    @CsvSource({
            "application/pdf, pdf",

            "application/vnd.openxmlformats-officedocument.wordprocessingml.document, doc",
            "application/msword, doc",
            "application/vnd.oasis.opendocument.text, doc",
            "application/rtf, doc",

            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet, sheet",
            "application/vnd.ms-excel, sheet",
            "application/vnd.oasis.opendocument.spreadsheet, sheet",

            "application/vnd.openxmlformats-officedocument.presentationml.presentation, slides",
            "application/vnd.openxmlformats-officedocument.presentationml.slideshow, slides",
            "application/vnd.ms-powerpoint, slides",
            "application/vnd.oasis.opendocument.presentation, slides",

            "image/jpeg, image",
            "image/png, image",
            "image/tiff, image",

            "message/rfc822, mail",
            "application/vnd.ms-outlook, mail",

            "text/plain, text",
            "text/html, text",
            "text/csv, text",

            "application/zip, other",
            "application/octet-stream, other"
    })
    void sortsTikaMimeTypesIntoFamilies(String mimeType, String expected) {
        assertThat(MimeTypeUtils.categorize(mimeType)).isEqualTo(expected);
    }

    @ParameterizedTest
    @NullAndEmptySource
    void fallsBackToOtherWhenTheMimeTypeIsMissing(String mimeType) {
        // SearchController passes "" when the meta_Content-Type field is absent
        assertThat(MimeTypeUtils.categorize(mimeType)).isEqualTo("other");
    }

    @Test
    void ignoresCaseAndTrailingParameters() {
        assertThat(MimeTypeUtils.categorize("Application/PDF; charset=binary")).isEqualTo("pdf");
        assertThat(MimeTypeUtils.categorize("text/plain; charset=UTF-8")).isEqualTo("text");
    }
}
