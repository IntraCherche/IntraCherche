package eu.intracherche.intracherche.indexing.enrichment;

import eu.intracherche.intracherche.common.constants.LanguageMetadataConstants;
import eu.intracherche.intracherche.indexing.model.IndexedDocument;
import eu.intracherche.intracherche.indexing.pipeline.PipelineContext;

import org.apache.tika.langdetect.optimaize.OptimaizeLangDetector;
import org.apache.tika.language.detect.LanguageDetector;
import org.apache.tika.language.detect.LanguageResult;
import org.apache.tika.metadata.Metadata;


import java.io.IOException;
import java.util.Set;

/**
 * Enricher for language detection
 */
public class LanguageDetectorEnricher implements DocumentEnricher {

    private static final LanguageDetector DETECTOR =
            new OptimaizeLangDetector().loadModels();

    private static final int MAX_SAMPLE_CHARS = 5_000;
    private static final double CONFIDENCE_THRESHOLD = 0.6;

    /**
    * Detects the language of the document content and adds language metadata to the document.
    *
    * This method analyzes the content of the document to determine its language. It uses a language
    * detection algorithm with confidence thresholding. If the detected language is reasonably
    * certain (above the confidence threshold), the language and confidence score are added to
    * the document's metadata.
    *
    * @param ctx the pipeline context containing the document to enrich
    * @throws IOException if there is an error reading the document content
    */
   @Override
   public void enrich(PipelineContext ctx)  {
       if (ctx == null) {
           return;
       }
       
       IndexedDocument doc = ctx.indexedDocument();
       
       // Defensive check to avoid NPE
       if (doc == null) {
           return;
       }

       String content = doc.content();
       if (content == null || content.isBlank()) {
           return;
       }

       String sample = sample(content);
       LanguageResult result = DETECTOR.detect(sample);

       if (!result.isReasonablyCertain()
               || result.getRawScore() < CONFIDENCE_THRESHOLD) {
           return;
       }

       Metadata newMetadata = new Metadata();
       if (doc.metadata() != null) {
           // Copy existing metadata
           for (String name : doc.metadata().names()) {
               newMetadata.add(name, doc.metadata().get(name));
           }
       }

       newMetadata.add(LanguageMetadataConstants.LANG_PRIMARY.getKey(), result.getLanguage());
       newMetadata.add(LanguageMetadataConstants.LANG_CONFIDENCE.getKey(), String.valueOf(result.getRawScore()));

       // Defensive check for potential nulls in the document fields
       String id = doc.id();
       if (id == null) {
           return;
       }
       
       var path = doc.path();
       if (path == null) {
           return;
       }
       
       var accessTokens = doc.accessTokens();
       if (accessTokens == null) {
           accessTokens = Set.of(); // Default to empty set
       }

       IndexedDocument updatedDocument = new IndexedDocument(
               id,
               path,
               doc.content(),
               newMetadata,
               accessTokens,
               doc.denyTokens(), // Pass through the deny tokens from the original document
               null, // indexSource - will be set during indexing
               doc.mtime(), // Pass through the mtime from the original document
               doc.smbServer(), // Pass through SMB server info
               doc.smbShare() // Pass through SMB share info
       );

       ctx.setIndexedDocument(updatedDocument);
   }

    private String sample(String content) {
        return content.length() <= MAX_SAMPLE_CHARS
                ? content
                : content.substring(0, MAX_SAMPLE_CHARS);
    }
}
