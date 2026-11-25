package eu.intracherche.intracherche.common;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.request.schema.SchemaRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Whether the running Solr cores have the {@code denyTokens} field (see the Deny-ACE fix).
 *
 * <p>The field is new: an upgrade from before that fix leaves existing cores on their old
 * schema until an admin pushes the new configset and reloads the cores (the app's own
 * reindex-all action). Until then, {@code -denyTokens:(...)} in a filter query fails the whole
 * query with "undefined field denyTokens" — every caller that would add that filter must check
 * this first and skip it while false, leaving the Deny-ACE gap open exactly as it was before
 * the fix, rather than breaking search outright.</p>
 *
 * <p>One process-wide flag is enough: every core in this app shares one pushed configset (see
 * reindex-all), so any single core answering "field exists" means all of them do. Self-heals:
 * once reindex-all runs, the next check notices the field and this flips true for the life of
 * the JVM — no restart needed.</p>
 */
public final class DenyTokensCapability {

    private static final Logger log = LoggerFactory.getLogger(DenyTokensCapability.class);
    private static final long RECHECK_INTERVAL_MS = 30_000;

    private static volatile boolean available = false;
    private static volatile long lastCheckedAt = 0;

    private DenyTokensCapability() {}

    /**
     * @param coreName any core known to exist; null/blank is treated as "can't check yet"
     * @return true once a core has been confirmed to carry the denyTokens field
     */
    public static boolean isAvailable(SolrClient solrClient, String coreName) {
        if (available) return true;
        if (solrClient == null || coreName == null || coreName.isBlank()) return false;

        long now = System.currentTimeMillis();
        if (now - lastCheckedAt < RECHECK_INTERVAL_MS) return false;
        lastCheckedAt = now;

        try {
            new SchemaRequest.Field("denyTokens").process(solrClient, coreName);
            available = true;
            log.info("Solr core '{}' has the denyTokens field: Deny ACEs are now enforced.", coreName);
        } catch (Exception e) {
            log.warn("Solr core '{}' has no denyTokens field yet — Deny ACEs are NOT enforced until " +
                    "reindex-all pushes the new schema and reloads the cores. ({})", coreName, e.toString());
        }
        return available;
    }

    /** For tests: force the next {@link #isAvailable} call to actually recheck Solr. */
    static void resetForTests() {
        available = false;
        lastCheckedAt = 0;
    }
}
