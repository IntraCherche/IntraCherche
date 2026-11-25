package eu.intracherche.intracherche.alerts;

import eu.intracherche.intracherche.alerts.model.SearchSubscription;
import eu.intracherche.intracherche.common.DenyTokensCapability;
import eu.intracherche.intracherche.common.SolrQuerySyntaxGuard;
import eu.intracherche.intracherche.config.IndexesConfig;
import eu.intracherche.intracherche.config.SolrConfig;
import org.apache.solr.client.solrj.SolrRequest;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.request.CoreAdminRequest;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;

@Service
public class ResultHashService {

    private static final Logger log = LoggerFactory.getLogger(ResultHashService.class);

    private final SolrClient solrClient;
    private final SolrConfig solrConfig;

    private final IndexesConfig indexesConfig;

    public ResultHashService(SolrClient solrClient, SolrConfig solrConfig, IndexesConfig indexesConfig) {
        this.solrClient = solrClient;
        this.solrConfig = solrConfig;
        this.indexesConfig = indexesConfig;
    }

    /**
     * Returns empty if any of the subscription's cores don't exist yet (cores are created lazily
     * during the first successful indexing cycle; this is a normal transient state).
     */
    public Optional<HashAndCount> computeHash(SearchSubscription sub, List<String> userSids)
            throws SolrServerException, IOException {

        // Only the indexes the subscriber may still see (indexes.yaml's visibleToGroups): an
        // alert saved before an index was restricted must not keep reading it for them.
        List<String> visible = indexesConfig.visibleCores(sub.indices(), userSids == null ? List.of() : userSids);
        if (visible.isEmpty()) {
            log.debug("Skipping subscription {} — none of its indexes {} is visible to its owner", sub.id(), sub.indices());
            return Optional.empty();
        }
        sub = sub.withIndices(visible);

        for (String coreName : sub.indices()) {
            if (!coreExists(coreName)) {
                log.debug("Skipping subscription {} — core '{}' not yet created", sub.id(), coreName);
                return Optional.empty();
            }
        }

        // Defence in depth: SubscriptionService.createSubscription already rejects this at
        // creation time, but a subscription saved before that check existed could still carry
        // disallowed syntax in its stored query — caught here, per-subscription, by the try/catch
        // in SubscriptionEvaluationService, same as any other evaluation failure.
        SolrQuerySyntaxGuard.check(sub.query());

        String primaryCore = sub.indices().get(0);
        boolean denyTokensAvailable = DenyTokensCapability.isAvailable(solrClient, primaryCore);
        SolrQuery query = SubscriptionQueryBuilder.build(sub, userSids, solrConfig.solrBaseUrl(), denyTokensAvailable);

        QueryResponse response = solrClient.query(primaryCore, query, SolrRequest.METHOD.POST);

        TreeSet<String> ids = new TreeSet<>();
        if (response.getResults() != null) {
            for (SolrDocument doc : response.getResults()) {
                Object pid = doc.getFieldValue("parent_id");
                if (pid != null) ids.add(pid.toString());
            }
        }

        String joined = String.join(",", ids);
        return Optional.of(new HashAndCount(sha256(joined), ids.size()));
    }

    private boolean coreExists(String coreName) {
        try {
            return CoreAdminRequest.getStatus(coreName, solrClient).getCoreStatus(coreName).size() > 0;
        } catch (SolrServerException | IOException e) {
            log.debug("Could not check core '{}': {}", coreName, e.getMessage());
            return false;
        }
    }

    private String sha256(String input) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
