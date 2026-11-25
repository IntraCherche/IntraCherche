package eu.intracherche.intracherche.indexing.parser;

import org.apache.tika.exception.WriteLimitReachedException;
import eu.intracherche.intracherche.common.MimeTypeUtils;
import eu.intracherche.intracherche.indexing.worker.DocumentCompletionTracker;
import eu.intracherche.intracherche.queues.OcrQueue;
import eu.intracherche.intracherche.services.OcrService;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.sax.BodyContentHandler;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.ocr.TesseractOCRConfig;
import org.xml.sax.helpers.DefaultHandler;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Document parser that delegates to format-specific extractors.
 *
 * <p>Supports two OCR modes:
 * <ul>
 *   <li><b>Synchronous</b>: Scanned PDF pages are OCR'd on the calling thread.</li>
 *   <li><b>Asynchronous</b>: Scanned PDF pages are enqueued into an OcrQueue for
 *       processing by dedicated OCR workers.</li>
 * </ul>
 * </p>
 */
public final class TikaDocumentParser implements DocumentParser {

    private final AutoDetectParser parser = new AutoDetectParser();
    private final OcrService ocrService;

    private static ParseContext noTesseractContext() {
        ParseContext ctx = new ParseContext();
        TesseractOCRConfig cfg = new TesseractOCRConfig();
        cfg.setSkipOcr(true);
        ctx.set(TesseractOCRConfig.class, cfg);
        return ctx;
    }

    /**
     * Create a TikaDocumentParser without OCR support.
     * Scanned PDF pages will be skipped with a warning.
     */
    public TikaDocumentParser() {
        this(null);
    }

    /**
     * Create a TikaDocumentParser with optional OCR support (synchronous mode).
     *
     * @param ocrService the OCR service for scanned PDF pages, or null to disable OCR
     */
    public TikaDocumentParser(OcrService ocrService) {
        this.ocrService = ocrService;
    }

    @Override
    public ParseResult parse(byte[] data, Metadata metadata) throws IOException {
        return parse(data, metadata, null, null, null, null, null, null, null, null, null, null);
    }

     public ParseResult parse(byte[] data, Metadata metadata,
                              OcrQueue ocrQueue,
                              String filePath,
                              String documentId,
                              String coreName,
                              java.util.Set<String> accessTokens,
                              java.util.Set<String> denyTokens,
                              Long mtime,
                              String smbServer,
                              String smbShare,
                              DocumentCompletionTracker completionTracker) throws IOException {
        return parse(data, metadata, ocrQueue, filePath, documentId, coreName, accessTokens, denyTokens,
                mtime, smbServer, smbShare, completionTracker, TextBudget.unlimited());
    }

    /** As above, keeping at most {@code budget} characters of text for the file. */
    public ParseResult parse(byte[] data, Metadata metadata,
                             OcrQueue ocrQueue,
                             String filePath,
                             String documentId,
                             String coreName,
                             java.util.Set<String> accessTokens,
                             java.util.Set<String> denyTokens,
                             Long mtime,
                             String smbServer,
                             String smbShare,
                             DocumentCompletionTracker completionTracker,
                             TextBudget budget) throws IOException {

        try (InputStream tikaStream = TikaInputStream.get(data)) {

            // First pass: detect MIME type only
            parser.parse(tikaStream, new DefaultHandler(), metadata, noTesseractContext());

            String detectedMimeType = metadata.get(Metadata.CONTENT_TYPE);
            List<IndexedSection> sections;
            int textPageCount;

            AtomicInteger ocrTaskCounter = new AtomicInteger(0);
            if ("application/pdf".equalsIgnoreCase(detectedMimeType)) {
                // Use PDFBox-based extractor with async OCR support; one section per text page
                sections = PdfPageExtractor.extractPages(
                        data,
                        ocrQueue,
                        filePath != null ? filePath : "",
                        ocrService,
                        documentId,
                        coreName,
                        metadata,
                        accessTokens,
                        denyTokens,
                        mtime,
                        smbServer,
                        smbShare,
                        ocrTaskCounter,
                        null,
                        null,
                        completionTracker,
                        budget
                );
                textPageCount = sections.size();
            }
            else if (MimeTypeUtils.isPresentation(detectedMimeType)) {
                // Handle PowerPoint and ODP; one section per slide
                sections = PresentationExtractor.extractSlides(data, detectedMimeType);
                textPageCount = sections.size();
            }
            else if (MimeTypeUtils.isTextDocument(detectedMimeType)) {
                // Handle Word (.docx, .doc) and OpenDocument (.odt); page count from \f splits
                WordTextExtractor.ExtractionResult wordResult = WordTextExtractor.extractPages(data, budget);
                sections = wordResult.sections();
                textPageCount = wordResult.pageCount();
            }
            else if (MimeTypeUtils.isSpreadsheet(detectedMimeType)) {
                // Handle Excel/ODS; one section per sheet
                sections = SpreadsheetExtractor.extractSheets(data);
                textPageCount = sections.size();
            }
            else if (MimeTypeUtils.isEmail(detectedMimeType)) {
                // Handle .eml/.msg; headers, then body chunks, then one section per attachment.
                // Also enriches `metadata` with the email.* keys IndexWorker maps to email_* fields.
                sections = EmailExtractor.extractMessage(data, metadata, noTesseractContext());
                textPageCount = sections.size();
            }
            else {
                // Fallback to Tika full-text, chunked by paragraph
                BodyContentHandler handler = new BodyContentHandler(budget.handlerLimit());

                try (InputStream fullParseStream = TikaInputStream.get(data)) {
                    parser.parse(fullParseStream, handler, metadata, noTesseractContext());
                } catch (Exception e) {
                    // The text budget is spent: keep what was extracted up to it.
                    if (!WriteLimitReachedException.isWriteLimitReached(e)) throw e;
                    budget.markTruncated();
                }

                String content = handler.toString().trim();
                if (content.isEmpty()) {
                    sections = List.of();
                } else {
                    List<String> chunks = TextChunker.chunkByParagraphs(content);
                    List<IndexedSection> result = new ArrayList<>();
                    for (int i = 0; i < chunks.size(); i++) {
                        result.add(new IndexedSection("Section " + (i + 1), chunks.get(i)));
                    }
                    sections = result;
                }
                textPageCount = sections.size();
            }

            // Formats that build their text whole (slides, sheets, e-mails) are cut here; the
            // others already stopped at the budget.
            TextBudget total = new TextBudget(budget.maxChars());
            sections = total.cap(sections);
            boolean limited = budget.truncated() || total.truncated();
            return new ParseResult(sections, metadata, detectedMimeType, ocrTaskCounter.get(), textPageCount, limited);

        } catch (Exception e) {
            if (data.length == 0 || isDocumentFault(e)) {
                throw new UnparsableDocumentException("Failed to parse document", e);
            }
            throw new IOException("Failed to parse document", e);
        }
    }

    /**
     * Whether the failure comes from the document's content (a Tika, POI, PDFBox or XML parser
     * refusing it) and not from something around it. Anything not recognised is treated as
     * transient: it is retried, as before.
     */
    static boolean isDocumentFault(Throwable e) {
        boolean parserRefusal = false;
        for (Throwable t = e; t != null; t = t.getCause()) {
            String name = t.getClass().getName();
            if (t instanceof InterruptedException || t instanceof java.net.SocketException
                    || t instanceof java.net.http.HttpTimeoutException || t instanceof java.nio.channels.ClosedChannelException
                    || name.endsWith("ServiceUnavailableException")) {
                return false;
            }
            if (t instanceof org.apache.tika.exception.TikaException || t instanceof org.xml.sax.SAXException
                    || name.startsWith("org.apache.poi.") || name.startsWith("org.apache.pdfbox.")) {
                parserRefusal = true;
            }
        }
        return parserRefusal;
    }
}