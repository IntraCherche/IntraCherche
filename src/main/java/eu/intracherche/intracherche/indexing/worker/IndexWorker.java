package eu.intracherche.intracherche.indexing.worker;

import eu.intracherche.intracherche.common.constants.EmailMetadataConstants;
import eu.intracherche.intracherche.common.constants.LanguageMetadataConstants;
import eu.intracherche.intracherche.common.constants.LocationMetadataConstants;
import eu.intracherche.intracherche.indexing.model.IndexTask;
import eu.intracherche.intracherche.indexing.parser.IndexedSection;
import eu.intracherche.intracherche.indexing.solr.SolrHeapWatch;
import eu.intracherche.intracherche.indexing.solr.SolrIndexer;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.common.SolrInputDocument;
import org.apache.tika.metadata.Metadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Dedicated worker that processes {@link IndexTask} instances by converting them
 * into {@link SolrInputDocument} instances and submitting them to Solr.
 *
 * <p>The IndexWorker is responsible <em>only</em> for:
 * <ul>
 *   <li>Taking IndexTask from the queue</li>
 *   <li>Converting IndexTask sections to SolrInputDocument</li>
 *   <li>Submitting documents to Solr via SolrIndexer</li>
 * </ul>
 * </p>
 *
 * <p>This class is designed to run within a thread managed by {@link IndexWorkerManager}.
 * Each worker runs continuously, pulling tasks from the shared queue until the
 * worker is signaled to stop.</p>
 *
 */
public class IndexWorker implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(IndexWorker.class);

    private final String workerName;
    private final SolrClient solrClient;
    private final int commitWithinMs;
    private final DocumentCompletionTracker completionTracker;
    private volatile boolean running = true;

    public IndexWorker(String workerName, SolrClient solrClient, int commitWithinMs) {
        this(workerName, solrClient, commitWithinMs, null);
    }

    /**
     * Creates a new IndexWorker with a completion tracker so that scanned-PDF
     * documents are marked as indexed only after their Solr write succeeds or fails.
     */
    public IndexWorker(String workerName, SolrClient solrClient, int commitWithinMs,
                       DocumentCompletionTracker completionTracker) {
        this.workerName = workerName;
        this.solrClient = solrClient;
        this.commitWithinMs = commitWithinMs;
        this.completionTracker = completionTracker;
    }

    /**
     * Signals this worker to stop processing after the current task completes.
     */
    public void stop() {
        this.running = false;
    }

    /**
     * Returns whether this worker is still running.
     */
    public boolean isRunning() {
        return running;
    }

    /**
     * Returns the name of this worker.
     */
    public String getWorkerName() {
        return workerName;
    }

    @Override
    public void run() {
        log.info("[{}] IndexWorker started", workerName);
        while (running) {
            try {
                // This method will be overridden by IndexWorkerManager to pull from the queue
                IndexTask task = dequeueTask();
                if (task != null) {
                    processTask(task);
                }
            } catch (InterruptedException e) {
                log.info("[{}] IndexWorker interrupted, shutting down gracefully", workerName);
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("[{}] Unexpected error in IndexWorker loop: {}", workerName, e.getMessage(), e);
            }
        }
        log.info("[{}] IndexWorker stopped", workerName);
    }

    /**
     * Dequeues the next IndexTask from the queue.
     * This is a template method that subclasses or the manager can override.
     */
    protected IndexTask dequeueTask() throws InterruptedException {
        // Default no-op implementation; overridden by IndexWorkerManager
        Thread.sleep(100);
        return null;
    }

    /**
     * Processes a single IndexTask by converting its sections to SolrInputDocument
     * instances and submitting them to the appropriate Solr core.
     *
     * @param task the IndexTask to process
     */
    protected void processTask(IndexTask task) {
        String docId = task.documentId();
        String coreName = task.coreName();

        log.debug("[{}] Processing IndexTask: document={}, core={}", workerName, docId, coreName);

        boolean solrSucceeded = false;
        try {
            // Convert IndexTask sections to SolrInputDocuments
            List<SolrInputDocument> solrDocs = convertToSolrDocuments(task);

            if (solrDocs.isEmpty()) {
                log.warn("[{}] No Solr documents generated for task: {}", workerName, docId);
                return;
            }

            // Submit to Solr using SolrIndexer
            SolrIndexer indexer = new SolrIndexer(solrClient, coreName, commitWithinMs);
            indexer.index(solrDocs);
            solrSucceeded = true;

            log.info("[{}] Successfully indexed {} sections for document {} into core {}",
                    workerName, solrDocs.size(), docId, coreName);

        } catch (SolrServerException e) {
            log.error("[{}] Solr server error indexing document {} into core {}: {}",
                    workerName, docId, coreName, e.getMessage(), e);
            SolrHeapWatch.explainIfHeapIsTight(solrClient, e);
        } catch (IOException e) {
            log.error("[{}] IO error indexing document {} into core {}: {}",
                    workerName, docId, coreName, e.getMessage(), e);
        } catch (Exception e) {
            log.error("[{}] Unexpected error processing IndexTask for document {}: {}",
                    workerName, docId, e.getMessage(), e);
        } finally {
            // Notify tracker (if present) that this Solr write has been attempted.
            // A document the tracker has no entry for (cancelled by a pause) is ignored.
            if (completionTracker != null) {
                completionTracker.solrPageDone(docId, solrSucceeded, task.isOcrResult());
            }
        }
    }

    /**
     * Converts an IndexTask into a list of SolrInputDocument instances.
     */
    List<SolrInputDocument> convertToSolrDocuments(IndexTask task) {
        List<SolrInputDocument> solrDocs = new ArrayList<>();

        List<IndexTask.SectionContent> sections = task.sections();
        if (sections == null || sections.isEmpty()) {
            // Fallback for empty documents
            solrDocs.add(createSolrDocument(task, new IndexTask.SectionContent("Document", "", false), 1, 1, null));
            return solrDocs;
        }

        List<float[]> vectors = task.sectionVectors();
        for (int i = 0; i < sections.size(); i++) {
            IndexTask.SectionContent section = sections.get(i);
            int pageNum = section.pageNumber() > 0 ? section.pageNumber() : i + 1;
            // An estimated page is shared by several sections: their position keeps their ids apart.
            int idNumber = section.estimatedPage() ? i + 1 : pageNum;
            float[] vector = (vectors != null && i < vectors.size()) ? vectors.get(i) : null;
            solrDocs.add(createSolrDocument(task, section, idNumber, pageNum, vector));
        }

        return solrDocs;
    }

    /**
     * Creates a single SolrInputDocument from an IndexTask and one of its sections.
     */
    private SolrInputDocument createSolrDocument(IndexTask task, IndexTask.SectionContent section, int idNumber,
                                                 int pageNumber, float[] vector) {
        SolrInputDocument solrDoc = new SolrInputDocument();

        // 1) Unique ID and grouping
        solrDoc.addField("id", task.documentId() + "_p" + idNumber);
        solrDoc.addField("parent_id", task.documentId());
        solrDoc.addField("page_number", pageNumber);

        // Use the label from the section
        String label = section.label() != null ? section.label() : "Page " + pageNumber;
        solrDoc.addField("location_label", label);
        solrDoc.addField("sheet_name", label);

        // Scanned flag
        solrDoc.addField("is_scanned", section.isScanned());

        // Path and Server info
        if (task.path() != null) {
            solrDoc.addField("path", task.path());
            solrDoc.addField("path_lc", task.path().toLowerCase());
        }
        if (task.smbServer() != null) {
            solrDoc.addField("smbServer", task.smbServer());
        }
        if (task.smbShare() != null) {
            solrDoc.addField("smbShare", task.smbShare());
        }

        // 2) Content
        if (section.content() != null && !section.content().isBlank()) {
            solrDoc.addField("content", section.content());
        }

        // 2b) Dense vector for hybrid (kNN) search
        // SolrJ serialises float[] as its toString() ("[F@..."); DenseVectorField requires List<Float>.
        if (vector != null) {
            List<Float> vectorList = new ArrayList<>(vector.length);
            for (float v : vector) vectorList.add(v);
            solrDoc.addField("content_vector", vectorList);
        }

        // 3) Metadata mapping
        Metadata metadata = task.metadata();
        if (metadata != null) {
            for (String name : metadata.names()) {
                if ("title".equals(name)) {
                    solrDoc.addField("title", metadata.get(name));
                } else if ("content_type".equals(name) || "mime_type".equals(name)) {
                    solrDoc.addField("mime_type", metadata.get(name));
                } else if (LocationMetadataConstants.GEO_CITIES.getKey().equals(name)) {
                    for (String v : metadata.getValues(name)) solrDoc.addField("geolocation_city", v);
                } else if (LocationMetadataConstants.GEO_DEPARTMENTS.getKey().equals(name)) {
                    for (String v : metadata.getValues(name)) solrDoc.addField("geolocation_dpt", v);
                } else if (LocationMetadataConstants.GEO_REGIONS.getKey().equals(name)) {
                    for (String v : metadata.getValues(name)) solrDoc.addField("geolocation_region", v);
                } else if (LocationMetadataConstants.GEO_COUNTRIES.getKey().equals(name)) {
                    for (String v : metadata.getValues(name)) solrDoc.addField("geolocation_country", v);
                } else if (EmailMetadataConstants.EMAIL_SUBJECT.getKey().equals(name)) {
                    solrDoc.addField("email_subject", metadata.get(name));
                } else if (EmailMetadataConstants.EMAIL_FROM.getKey().equals(name)) {
                    for (String v : metadata.getValues(name)) solrDoc.addField("email_from", v);
                } else if (EmailMetadataConstants.EMAIL_TO.getKey().equals(name)) {
                    for (String v : metadata.getValues(name)) solrDoc.addField("email_to", v);
                } else if (EmailMetadataConstants.EMAIL_DATE.getKey().equals(name)) {
                    solrDoc.addField("email_date", metadata.get(name));
                } else if (LanguageMetadataConstants.LANG_PRIMARY.getKey().equals(name)) {
                    solrDoc.addField("lang", metadata.get(name));
                } else if (LanguageMetadataConstants.LANG_CONFIDENCE.getKey().equals(name)) {
                    // confidence score — not indexed
                } else {
                    solrDoc.addField("meta_" + name, metadata.get(name));
                }
            }
        }

        // 4) Security & Source
        if (task.accessTokens() != null && !task.accessTokens().isEmpty()) {
            solrDoc.addField("accessTokens", task.accessTokens());
        }
        if (task.denyTokens() != null && !task.denyTokens().isEmpty()) {
            solrDoc.addField("denyTokens", task.denyTokens());
        }
        if (task.coreName() != null) {
            solrDoc.addField("index_source", task.coreName());
        }
        if (task.mtime() != null) {
            solrDoc.addField("mtime", task.mtime());
        }

        return solrDoc;
    }
}