package eu.intracherche.intracherche.indexing.parser;

import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.SocketException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UnparsableDocumentTest {

    private final TikaDocumentParser parser = new TikaDocumentParser();

    @Test
    void an_empty_file_is_unparsable() {
        assertThatThrownBy(() -> parser.parse(new byte[0], new Metadata()))
                .isInstanceOf(UnparsableDocumentException.class);
    }

    @Test
    void a_damaged_office_file_is_unparsable() {
        // Starts like an OLE2 file (.ppt, .doc) and goes to garbage: what the corrupt slides of the demo look like.
        byte[] data = new byte[4096];
        byte[] magic = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1};
        System.arraycopy(magic, 0, data, 0, magic.length);
        for (int i = magic.length; i < data.length; i++) data[i] = (byte) (i * 31);

        assertThatThrownBy(() -> parser.parse(data, new Metadata()))
                .isInstanceOf(UnparsableDocumentException.class);
    }

    @Test
    void a_readable_text_file_still_parses() throws IOException {
        var result = parser.parse("Bonjour le monde".getBytes(), new Metadata());

        assertThat(result.sections()).isNotEmpty();
    }

    @Test
    void only_a_parser_refusing_the_content_is_a_document_fault() {
        assertThat(TikaDocumentParser.isDocumentFault(new IOException("x", new TikaException("bad")))).isTrue();
        // The same refusal while the network was failing is not the document's fault.
        assertThat(TikaDocumentParser.isDocumentFault(
                new TikaException("bad", new SocketException("reset")))).isFalse();
        assertThat(TikaDocumentParser.isDocumentFault(new IOException("disk"))).isFalse();
        assertThat(TikaDocumentParser.isDocumentFault(new IllegalStateException("?"))).isFalse();
    }
}
