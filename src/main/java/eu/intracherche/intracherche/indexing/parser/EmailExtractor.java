package eu.intracherche.intracherche.indexing.parser;

import eu.intracherche.intracherche.common.constants.EmailMetadataConstants;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Message;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.RecursiveParserWrapper;
import org.apache.tika.sax.BasicContentHandlerFactory;
import org.apache.tika.sax.RecursiveParserWrapperHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Extractor for email messages ({@code .eml} via RFC822, {@code .msg} via MAPI).
 *
 * <p>Produces one section for the header block, then one section per body chunk,
 * then one section per attachment. Keeping the headers in their own section means a
 * search for a sender's name matches the message even when the name never appears in
 * the body, and the snippet the operator sees names who wrote what.</p>
 *
 * <p>Attachments are parsed recursively, so a PDF or Word file sent as an attachment is
 * searchable through its parent message. Attachment sections are labelled with the
 * filename so the result line says where the text came from.</p>
 *
 * <p>The subject, sender and date are also written back onto the container
 * {@link Metadata} under the {@link EmailMetadataConstants} keys, which
 * {@code IndexWorker} maps to the {@code email_*} Solr fields.</p>
 */
public final class EmailExtractor {

    private static final Logger log = LoggerFactory.getLogger(EmailExtractor.class);

    /**
     * Ceiling on how many embedded parts we walk into. A mail bomb (or a long thread where
     * every reply re-attaches the same deck) should not be able to turn one file into
     * thousands of Solr documents.
     */
    private static final int MAX_EMBEDDED_PARTS = 50;

    private EmailExtractor() {}

    /**
     * Parse an email message into sections and normalise its headers onto {@code metadata}.
     *
     * @param data     the raw message bytes
     * @param metadata the container metadata, enriched in place with the {@code email.*} keys
     * @param context  the parse context to use (carries the OCR settings from the caller)
     * @return the header section, body sections and attachment sections, in that order
     */
    public static List<IndexedSection> extractMessage(byte[] data, Metadata metadata, ParseContext context)
            throws Exception {

        RecursiveParserWrapper wrapper = new RecursiveParserWrapper(new AutoDetectParser());
        RecursiveParserWrapperHandler handler = new RecursiveParserWrapperHandler(
                new BasicContentHandlerFactory(BasicContentHandlerFactory.HANDLER_TYPE.TEXT, -1),
                MAX_EMBEDDED_PARTS);

        Metadata containerMetadata = new Metadata();
        try (InputStream stream = TikaInputStream.get(data)) {
            wrapper.parse(stream, handler, containerMetadata, context);
        }

        // Index 0 is always the container (the message itself); the rest are its parts,
        // in the order Tika walked them.
        List<Metadata> parts = handler.getMetadataList();
        if (parts.isEmpty()) {
            return List.of();
        }
        Metadata container = parts.get(0);

        copyHeadersTo(container, metadata);

        List<IndexedSection> sections = new ArrayList<>();

        String headerBlock = headerBlock(container);
        if (!headerBlock.isBlank()) {
            sections.add(new IndexedSection("Headers", headerBlock));
        }

        String body = container.get(TikaCoreProperties.TIKA_CONTENT);
        appendChunks(sections, body, "Message");

        for (int i = 1; i < parts.size(); i++) {
            Metadata part = parts.get(i);
            String text = part.get(TikaCoreProperties.TIKA_CONTENT);
            if (text == null || text.isBlank()) continue;

            String name = part.get(TikaCoreProperties.RESOURCE_NAME_KEY);
            String label = "Attachment: " + (name != null && !name.isBlank() ? name : "part " + i);
            appendChunks(sections, text, label);
        }

        return sections;
    }

    /**
     * Chunk {@code text} and append one section per chunk, numbering them only when
     * there is more than one (a short message stays a single "Message" section).
     */
    private static void appendChunks(List<IndexedSection> sections, String text, String label) {
        if (text == null || text.isBlank()) return;

        List<String> chunks = TextChunker.chunkByParagraphs(text.trim());
        for (int i = 0; i < chunks.size(); i++) {
            String chunkLabel = chunks.size() == 1 ? label : label + " " + (i + 1);
            sections.add(new IndexedSection(chunkLabel, chunks.get(i)));
        }
    }

    /**
     * Render the headers as the plain-text block that becomes the first section.
     */
    private static String headerBlock(Metadata container) {
        StringBuilder block = new StringBuilder();

        String subject = subject(container);
        if (subject != null) block.append("Subject: ").append(subject).append("\n");

        List<String> from = addresses(container, Message.MESSAGE_FROM, TikaCoreProperties.CREATOR.getName());
        if (!from.isEmpty()) block.append("From: ").append(String.join(", ", from)).append("\n");

        List<String> to = addresses(container, Message.MESSAGE_TO);
        if (!to.isEmpty()) block.append("To: ").append(String.join(", ", to)).append("\n");

        List<String> cc = addresses(container, Message.MESSAGE_CC);
        if (!cc.isEmpty()) block.append("Cc: ").append(String.join(", ", cc)).append("\n");

        String sentAt = sentAt(container);
        if (sentAt != null) block.append("Date: ").append(sentAt).append("\n");

        return block.toString().trim();
    }

    /**
     * Write the normalised {@code email.*} keys onto the metadata the pipeline carries.
     */
    private static void copyHeadersTo(Metadata container, Metadata target) {
        if (target == null) return;

        String subject = subject(container);
        if (subject != null) {
            target.set(EmailMetadataConstants.EMAIL_SUBJECT.getKey(), subject);
        }
        for (String from : addresses(container, Message.MESSAGE_FROM, TikaCoreProperties.CREATOR.getName())) {
            target.add(EmailMetadataConstants.EMAIL_FROM.getKey(), from);
        }
        for (String to : addresses(container, Message.MESSAGE_TO)) {
            target.add(EmailMetadataConstants.EMAIL_TO.getKey(), to);
        }
        String sentAt = sentAt(container);
        if (sentAt != null) {
            target.set(EmailMetadataConstants.EMAIL_DATE.getKey(), sentAt);
        }
    }

    /**
     * The subject. Both the RFC822 and the Outlook parser put it in {@code dc:title}.
     */
    private static String subject(Metadata container) {
        String subject = container.get(TikaCoreProperties.TITLE);
        return subject != null && !subject.isBlank() ? subject.trim() : null;
    }

    /**
     * Collect the non-blank values of the first key that has any, de-duplicated and in order.
     * A message with several {@code From:} values is malformed but does occur in the wild.
     */
    private static List<String> addresses(Metadata container, String... keys) {
        for (String key : keys) {
            Set<String> values = new LinkedHashSet<>();
            for (String value : container.getValues(key)) {
                if (value != null && !value.isBlank()) values.add(value.trim());
            }
            if (!values.isEmpty()) return List.copyOf(values);
        }
        return List.of();
    }

    /**
     * The sent date as an ISO-8601 UTC instant, or null when the header is missing or unparseable.
     * Tika has already turned the RFC822/MAPI date into a {@link Date} for us.
     */
    private static String sentAt(Metadata container) {
        Date created = container.getDate(TikaCoreProperties.CREATED);
        if (created == null) {
            return null;
        }
        try {
            return DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(created.getTime()));
        } catch (RuntimeException e) {
            // A date outside the representable range — index the message without one
            // rather than failing the whole file.
            log.warn("Unusable email date {}: {}", created, e.toString());
            return null;
        }
    }
}
