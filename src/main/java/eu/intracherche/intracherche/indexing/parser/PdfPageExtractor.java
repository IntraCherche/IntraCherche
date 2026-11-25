package eu.intracherche.intracherche.indexing.parser;

import eu.intracherche.intracherche.indexing.model.OCRTask;
import eu.intracherche.intracherche.indexing.worker.DocumentCompletionTracker;
import eu.intracherche.intracherche.queues.OcrQueue;
import eu.intracherche.intracherche.services.OcrService;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.apache.tika.metadata.Metadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * PDF page-level extractor that detects scanned (image-only) pages
 * and routes them through OCR when text extraction yields nothing.
 *
 * <p>This class supports two modes of operation:
 * <ul>
 *   <li><b>Synchronous OCR</b>: When called with only an OcrService, scanned pages
 *       are processed synchronously (blocking the calling thread).</li>
 *   <li><b>Asynchronous OCR</b>: When called with an OcrQueue, scanned pages are
 *       enqueued as OCRTask instances for async processing by dedicated OCR workers.</li>
 * </ul>
 * </p>
 *
 * <p>In async mode, only non-scanned pages are returned in the result list.
 * Scanned pages will be indexed later by the OCR workers after OCR completion.</p>
 */
public class PdfPageExtractor {

    private static final Logger log = LoggerFactory.getLogger(PdfPageExtractor.class);

    /**
     * Minimum character count threshold. Pages with very little text
     * (e.g., just a header or footer) are treated as potentially scanned
     * if they also contain images.
     */
    private static final int MIN_TEXT_LENGTH_FOR_NON_SCANNED = 20;

    /**
     * Extract pages from a PDF, detecting scanned pages and running OCR where needed.
     * This is the synchronous version that blocks on OCR.
     *
     * @param data       the raw PDF bytes
     * @param ocrService the OCR service to use for scanned pages (may be null to skip OCR)
     * @return list of IndexedSection, one per page that has content
     */
    public static List<IndexedSection> extractPages(byte[] data, OcrService ocrService) throws Exception {
        return extractPages(data, null, null, ocrService, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    /**
     * Extract pages from a PDF with filePath but no async OCR queue.
     *
     * @param data       the raw PDF bytes
     * @param filePath   virtual or physical path string
     * @param ocrService the OCR service to use for scanned pages (may be null to skip OCR)
     * @return list of IndexedSection, one per page that has content
     */
    public static List<IndexedSection> extractPages(byte[] data, String filePath, OcrService ocrService) throws Exception {
        return extractPages(data, null, filePath, ocrService, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    public static List<IndexedSection> extractPages(byte[] data, String filePath, OcrService ocrService,
                                                    AtomicInteger pageProgress) throws Exception {
        return extractPages(data, null, filePath, ocrService, null, null, null, null, null, null, null, null, null, pageProgress, null, null);
    }

    public static List<IndexedSection> extractPages(byte[] data, String filePath, OcrService ocrService,
                                                    AtomicInteger pageProgress, AtomicInteger outTotalPages) throws Exception {
        return extractPages(data, null, filePath, ocrService, null, null, null, null, null, null, null, null, null, pageProgress, outTotalPages, null);
    }

    /**
     * Legacy overload for callers that don't need OCR (e.g., tests).
     */
    public static List<IndexedSection> extractPages(byte[] data) throws Exception {
        return extractPages(data, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    /**
     * Extract pages from a PDF with support for asynchronous OCR.
     *
     * <p>When an {@link OcrQueue} is provided, scanned pages are enqueued as
     * {@link OCRTask} instances for async processing by dedicated OCR workers.
     * Only non-scanned pages are returned in the result list.</p>
     *
     * <p>When no OcrQueue is provided but an OcrService is available, scanned
     * pages are processed synchronously (blocking the calling thread).</p>
     *
     * @param data           the raw PDF bytes
     * @param ocrQueue       the OCR queue for async processing (may be null for synchronous mode)
     * @param filePath       virtual or physical path string (for IndexTask display/references)
     * @param ocrService     the OCR service for synchronous OCR (may be null)
     * @param documentId     unique document identifier
     * @param coreName       Solr core name for indexing
     * @param metadata       document metadata
     * @param accessTokens   access tokens for document security
     * @param denyTokens     SIDs with an explicit Deny ACE (Windows/SMB only; always empty for POSIX)
     * @param mtime          file modification time
     * @param smbServer      SMB server host (nullable)
     * @param smbShare       SMB share name (nullable)
     * @return list of IndexedSection for non-scanned pages only (scanned pages are enqueued for async OCR)
     */
    public static List<IndexedSection> extractPages(
            byte[] data,
            OcrQueue ocrQueue,
            String filePath,
            OcrService ocrService,
            String documentId,
            String coreName,
            Metadata metadata,
            java.util.Set<String> accessTokens,
            java.util.Set<String> denyTokens,
            Long mtime,
            String smbServer,
            String smbShare,
            AtomicInteger ocrTaskCounter,
            AtomicInteger pageProgress,
            AtomicInteger outTotalPages,
            DocumentCompletionTracker completionTracker
    ) throws Exception {
        return extractPages(data, ocrQueue, filePath, ocrService, documentId, coreName, metadata, accessTokens,
                denyTokens, mtime, smbServer, smbShare, ocrTaskCounter, pageProgress, outTotalPages,
                completionTracker, TextBudget.unlimited());
    }

    /** As above, stopping at the pages past {@code budget}: neither their text nor their OCR is kept. */
    public static List<IndexedSection> extractPages(
            byte[] data,
            OcrQueue ocrQueue,
            String filePath,
            OcrService ocrService,
            String documentId,
            String coreName,
            Metadata metadata,
            java.util.Set<String> accessTokens,
            java.util.Set<String> denyTokens,
            Long mtime,
            String smbServer,
            String smbShare,
            AtomicInteger ocrTaskCounter,
            AtomicInteger pageProgress,
            AtomicInteger outTotalPages,
            DocumentCompletionTracker completionTracker,
            TextBudget budget
    ) throws Exception {
        List<IndexedSection> sections = new ArrayList<>();

        try (PDDocument document = Loader.loadPDF(data)) {
            int totalPages = document.getNumberOfPages();
            if (outTotalPages != null) outTotalPages.set(totalPages);
            PDFTextStripper stripper = new PDFTextStripper() {
                @Override
                protected void writeString(String text, List<TextPosition> positions) throws IOException {
                    super.writeString(PrivateUseGlyphs.restore(text, positions), positions);
                }
            };
            PDFRenderer pdfRenderer = new PDFRenderer(document);

            // Pre-scan: extract text and detect images for all pages before enqueuing
            // any OCR task. This lets us register the completion tracker up front so
            // that completions arriving on worker threads before parse() returns are
            // never silently dropped.
            String[]  pageTexts  = new String[totalPages + 1];
            boolean[] pageImages = new boolean[totalPages + 1];
            for (int i = 1; i <= totalPages; i++) {
                if (budget.exhausted()) {
                    // Past the file's text budget: the page is left out, OCR included.
                    budget.markTruncated();
                    pageTexts[i]  = "";
                    pageImages[i] = false;
                    continue;
                }
                stripper.setStartPage(i);
                stripper.setEndPage(i);
                pageTexts[i]  = budget.take(stripper.getText(document).trim());
                pageImages[i] = pageHasImages(document.getPage(i - 1));
            }

            if (completionTracker != null && ocrQueue != null && documentId != null) {
                int scannedCount = 0;
                for (int i = 1; i <= totalPages; i++) {
                    if (pageTexts[i].length() < MIN_TEXT_LENGTH_FOR_NON_SCANNED && pageImages[i]) {
                        org.apache.pdfbox.pdmodel.common.PDRectangle box = document.getPage(i - 1).getCropBox();
                        long w = (long)(box.getWidth()  / 72f * 300f);
                        long h = (long)(box.getHeight() / 72f * 300f);
                        if (w * h <= 50_000_000L) scannedCount++;
                    }
                }
                if (scannedCount > 0) {
                    completionTracker.register(documentId, scannedCount, coreName, filePath);
                }
            }

            for (int i = 1; i <= totalPages; i++) {
                String text       = pageTexts[i];
                PDPage page       = document.getPage(i - 1);
                boolean hasImages = pageImages[i];

                boolean isScannedPage = text.length() < MIN_TEXT_LENGTH_FOR_NON_SCANNED && hasImages;

                String label = "Page " + i;

                // Case 1: Text extracted successfully — normal text-based page
                if (!isScannedPage && !text.isEmpty()) {
                    sections.add(new IndexedSection(label, text, false, i));
                }
                // Case 2: Scanned page detected
                else if (isScannedPage) {
                    if (ocrQueue != null) {
                        // ASYNC MODE: render the page to PNG here (PDF is already open) and
                        // enqueue only the small image — not the full PDF bytes.
                        log.info("Detected scanned page {} (images present, no text layer). Rendering and enqueueing for async OCR...", i);
                        try {
                            org.apache.pdfbox.pdmodel.common.PDRectangle box = page.getCropBox();
                            long widthPx  = (long)(box.getWidth()  / 72f * 300f);
                            long heightPx = (long)(box.getHeight() / 72f * 300f);
                            if (widthPx * heightPx > 50_000_000L) {
                                log.warn("Skipping render of page {} for document={}: page dimensions too large for OCR ({}×{} px at 300 DPI)",
                                        i, filePath, widthPx, heightPx);
                                if (pageProgress != null) pageProgress.incrementAndGet();
                                continue;
                            }
                            java.awt.image.BufferedImage image = pdfRenderer.renderImageWithDPI(i - 1, 300f);
                            byte[] pngBytes;
                            try (java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream()) {
                                javax.imageio.ImageIO.write(image, "png", baos);
                                pngBytes = baos.toByteArray();
                            }
                            OCRTask ocrTask = OCRTask.of(
                                    documentId,
                                    coreName,
                                    filePath,
                                    pngBytes,
                                    i,
                                    metadata,
                                    accessTokens,
                                    denyTokens,
                                    mtime,
                                    smbServer,
                                    smbShare
                            );
                            ocrQueue.publish(ocrTask);
                            if (ocrTaskCounter != null) ocrTaskCounter.incrementAndGet();
                            log.info("Enqueued OCRTask for document={} page={} pngSize={} bytes", documentId, i, pngBytes.length);
                        } catch (InterruptedException e) {
                            log.error("Interrupted while enqueuing OCRTask for document={} page={}", documentId, i);
                            Thread.currentThread().interrupt();
                            throw new RuntimeException("Interrupted while enqueuing OCRTask", e);
                        }
                    } else if (ocrService != null) {
                        // SYNC MODE: Process OCR synchronously (legacy behavior)
                        log.info("Detected scanned page {} (images present, no text layer). Running synchronous OCR...", i);
                        org.apache.pdfbox.pdmodel.common.PDRectangle box = page.getCropBox();
                        long widthPx  = (long)(box.getWidth()  / 72f * 300f);
                        long heightPx = (long)(box.getHeight() / 72f * 300f);
                        if (widthPx * heightPx > 50_000_000L) {
                            log.warn("Skipping render of page {} for document={}: page dimensions too large for OCR ({}×{} px at 300 DPI)",
                                    i, filePath, widthPx, heightPx);
                            if (pageProgress != null) pageProgress.incrementAndGet();
                            continue;
                        }
                        long ocrStartTime = System.currentTimeMillis();
                        String ocrText = performOcr(pdfRenderer, i - 1, ocrService);
                        long ocrDuration = System.currentTimeMillis() - ocrStartTime;
                        if (ocrText != null && !ocrText.isEmpty()) {
                            sections.add(new IndexedSection(label, ocrText, true, i));
                            log.info("OCR extracted {} characters from scanned page {} in {} ms", ocrText.length(), i, ocrDuration);
                        } else {
                            log.warn("OCR returned empty result for scanned page {}", i);
                        }
                    } else {
                        log.warn("Scanned page {} detected but no OCR service or queue available. Skipping.", i);
                    }
                }
                // Blank page — silently skip

                if (pageProgress != null) pageProgress.incrementAndGet();
            }
        }

        return sections;
    }

    /**
     * Check if a PDF page contains any image XObjects.
     * Uses PDFBox 3.x API safely.
     */
    private static boolean pageHasImages(PDPage page) {
        if (page.getResources() == null) {
            return false;
        }

        // Iterate through the internal PDF resource names mapped to XObjects
        for (COSName name : page.getResources().getXObjectNames()) {
            try {
                PDXObject xobject = page.getResources().getXObject(name);
                if (xobject instanceof PDImageXObject) {
                    return true;
                }
            } catch (Exception e) {
                log.warn("Failed to evaluate XObject named '{}': {}", name.getName(), e.getMessage());
            }
        }
        return false;
    }

    /**
     * Render a single PDF page to a PNG image and send it to the OCR service.
     * Used only in synchronous mode.
     */
    private static String performOcr(PDFRenderer renderer, int pageIndex, OcrService ocrService) {
        try {
            // Render page to BufferedImage at high DPI for good OCR quality
            java.awt.image.BufferedImage image = renderer.renderImageWithDPI(pageIndex, 300f);

            // Convert to PNG bytes
            try (java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream()) {
                javax.imageio.ImageIO.write(image, "png", baos);
                byte[] pngBytes = baos.toByteArray();

                return ocrService.extractTextFromImage(pngBytes);
            }

        } catch (Exception e) {
            log.error("OCR failed for page {}: {}", pageIndex + 1, e.getMessage());
            return null;
        }
    }
}