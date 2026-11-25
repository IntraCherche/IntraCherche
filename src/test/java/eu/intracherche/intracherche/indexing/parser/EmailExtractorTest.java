package eu.intracherche.intracherche.indexing.parser;

import eu.intracherche.intracherche.common.constants.EmailMetadataConstants;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.ParseContext;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for {@link EmailExtractor}: the subject/sender/date normalisation and the
 * section layout (headers, body, attachments) that the operator ends up searching.
 */
class EmailExtractorTest {

    private static final String SIMPLE_EML = """
            From: Marie Dupont <marie.dupont@mairie-cherche.fr>
            To: Service Technique <technique@mairie-cherche.fr>
            Cc: Paul Martin <paul.martin@mairie-cherche.fr>
            Subject: Reunion budget 2027
            Date: Wed, 16 Sep 2026 08:30:00 +0000
            Content-Type: text/plain; charset=UTF-8

            Bonjour,

            La reunion sur le budget 2027 est reportee au 24 septembre.

            Cordialement,
            Marie
            """;

    private static List<IndexedSection> extract(String eml, Metadata metadata) throws Exception {
        return EmailExtractor.extractMessage(
                eml.getBytes(StandardCharsets.UTF_8), metadata, new ParseContext());
    }

    @Test
    void normalisesSubjectSenderAndDateOntoTheMetadata() throws Exception {
        Metadata metadata = new Metadata();

        extract(SIMPLE_EML, metadata);

        assertThat(metadata.get(EmailMetadataConstants.EMAIL_SUBJECT.getKey()))
                .isEqualTo("Reunion budget 2027");
        assertThat(metadata.getValues(EmailMetadataConstants.EMAIL_FROM.getKey()))
                .containsExactly("Marie Dupont <marie.dupont@mairie-cherche.fr>");
        assertThat(metadata.getValues(EmailMetadataConstants.EMAIL_TO.getKey()))
                .containsExactly("Service Technique <technique@mairie-cherche.fr>");
        // ISO-8601 in UTC, so Solr parses it as a pdate rather than storing it as text
        assertThat(metadata.get(EmailMetadataConstants.EMAIL_DATE.getKey()))
                .isEqualTo("2026-09-16T08:30:00Z");
    }

    @Test
    void putsTheHeadersInTheirOwnFirstSection() throws Exception {
        List<IndexedSection> sections = extract(SIMPLE_EML, new Metadata());

        assertThat(sections).isNotEmpty();
        IndexedSection headers = sections.get(0);
        assertThat(headers.label()).isEqualTo("Headers");
        assertThat(headers.content())
                .contains("Subject: Reunion budget 2027")
                .contains("From: Marie Dupont <marie.dupont@mairie-cherche.fr>")
                .contains("To: Service Technique <technique@mairie-cherche.fr>")
                .contains("Cc: Paul Martin <paul.martin@mairie-cherche.fr>")
                .contains("Date: 2026-09-16T08:30:00Z");
    }

    @Test
    void keepsTheBodyInASectionOfItsOwn() throws Exception {
        List<IndexedSection> sections = extract(SIMPLE_EML, new Metadata());

        List<IndexedSection> body = sections.stream()
                .filter(s -> s.label().startsWith("Message"))
                .toList();

        assertThat(body).hasSize(1);
        assertThat(body.get(0).content()).contains("budget 2027 est reportee au 24 septembre");
        // The header block must not be duplicated into the body section
        assertThat(body.get(0).content()).doesNotContain("Subject:");
    }

    @Test
    void indexesAttachmentTextUnderTheAttachmentFilename() throws Exception {
        String attachment = Base64.getEncoder().encodeToString(
                "Devis chaudiere: 12400 euros TTC".getBytes(StandardCharsets.UTF_8));
        String eml = """
                From: fournisseur@example.com
                To: technique@mairie-cherche.fr
                Subject: Devis
                Date: Wed, 16 Sep 2026 08:30:00 +0000
                Content-Type: multipart/mixed; boundary="SEP"

                --SEP
                Content-Type: text/plain; charset=UTF-8

                Veuillez trouver le devis en piece jointe.

                --SEP
                Content-Type: text/plain; charset=UTF-8
                Content-Disposition: attachment; filename="devis.txt"
                Content-Transfer-Encoding: base64

                %s

                --SEP--
                """.formatted(attachment);

        List<IndexedSection> sections = extract(eml, new Metadata());

        assertThat(sections)
                .anySatisfy(section -> {
                    assertThat(section.label()).isEqualTo("Attachment: devis.txt");
                    assertThat(section.content()).contains("12400 euros TTC");
                });
    }

    @Test
    void survivesAMessageWithNoHeadersAtAll() throws Exception {
        Metadata metadata = new Metadata();

        List<IndexedSection> sections = extract("\r\nJuste du texte, sans en-tete.\r\n", metadata);

        assertThat(metadata.get(EmailMetadataConstants.EMAIL_SUBJECT.getKey())).isNull();
        assertThat(metadata.get(EmailMetadataConstants.EMAIL_DATE.getKey())).isNull();
        assertThat(sections).noneMatch(s -> s.label().equals("Headers"));
    }
}
