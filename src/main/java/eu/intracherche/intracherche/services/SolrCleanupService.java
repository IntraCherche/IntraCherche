package eu.intracherche.intracherche.services;

import eu.intracherche.intracherche.registry.FileRegistry;
import org.apache.solr.client.solrj.SolrClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class SolrCleanupService {
    private static final Logger log = LoggerFactory.getLogger(SolrCleanupService.class);


    private final SolrClient solrClient;
    private final FileRegistry fileRegistry;

    public SolrCleanupService(SolrClient solrClient, FileRegistry fileRegistry) {
        this.solrClient = solrClient;
        this.fileRegistry = fileRegistry;
    }

    public void cleanStalledFiles(String coreName, String shareName, int thresholdHours, int safetyValveThresholdPercent) throws Exception {
        long threshold = System.currentTimeMillis() - (thresholdHours * 3600000L);

//        // test in minute
//        long threshold = System.currentTimeMillis() - (thresholdHours * 60000L);

        long stalledCount = fileRegistry.countStalledFiles(shareName, threshold);
        long totalInShare = fileRegistry.countFilesByShare(shareName);

        if (totalInShare == 0) {
            log.debug("No files found in registry for share {}. Skipping cleanup.", shareName);
            return;
        }

        double percentStalled = (double) stalledCount / totalInShare;
        double safetyValveRatio = safetyValveThresholdPercent / 100.0;

        // Safety Valve: prevents mass-deletion if the server was just offline.
        // Configurable via registry-config.safetyValveThresholdPercent (default 30).
        if (percentStalled <= safetyValveRatio) {
            if (stalledCount > 0) {
                log.info("Deleting {} stalled files for share {} ({}% of total)",
                        stalledCount, shareName, Math.round(percentStalled * 100));

                // One Solr query per batch, one OR clause per file: it must stay under Solr's
                // maxBooleanClauses (1024), or the whole cleanup, and with it the cycle, fails.
                int batchSize = 500;
                for (int offset = 0; offset < stalledCount; offset += batchSize) {
                    List<String> idsBatch = fileRegistry.getStalledSolrIds(shareName, threshold, batchSize, offset);

                    // If the batch is empty, it just means these specific stalled files
                    // weren't in Solr. We don't want to 'break' the whole process;
                    // we just skip the Solr call.
                    if (!idsBatch.isEmpty()) {
                        // Solr stores one document per page as "{registryId}_p{n}", grouped under parent_id={registryId}.
                        // We must delete by parent_id query, not by the raw registry ID.
                        String query = "parent_id:(" + String.join(" OR ", idsBatch) + ")";
                        solrClient.deleteByQuery(coreName, query, 1000);
                        log.debug("Sent batch of {} parent deletions to Solr", idsBatch.size());
                    } else {
                        log.debug("No registry IDs found for this batch of stalled files. They might have failed indexing.");
                    }
                }

                // Final Step: Purge the Registry
                fileRegistry.deleteStalledFiles(shareName, threshold);
                log.info("Cleanup complete for share {}", shareName);
            } else {
                log.info("No stalled files found for share {}.", shareName);
            }
        } else {
            log.warn("SAFETY VALVE TRIGGERED: {}% of files (count: {}) are stalled in share {} " +
                            "(threshold: {}%). Deletion aborted to prevent accidental index wipe. " +
                            "Raise registry-config.safetyValveThresholdPercent if this is intentional.",
                    Math.round(percentStalled * 100), stalledCount, shareName, safetyValveThresholdPercent);
        }
    }
}


