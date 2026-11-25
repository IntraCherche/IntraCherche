package eu.intracherche.intracherche.alerts;

import eu.intracherche.intracherche.alerts.dao.SubscriptionDao;
import eu.intracherche.intracherche.alerts.model.NotificationFrequency;
import eu.intracherche.intracherche.alerts.model.SearchSubscription;
import eu.intracherche.intracherche.common.SolrQuerySyntaxGuard;
import eu.intracherche.intracherche.security.SecurityService;
import eu.intracherche.intracherche.web.dto.CreateSubscriptionRequest;
import org.springframework.stereotype.Service;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

@Service
public class SubscriptionService {

    private final SubscriptionDao subscriptionDao;
    private final SecurityService securityService;
    private final SubscriptionEvaluationService evaluationService;

    public SubscriptionService(SubscriptionDao subscriptionDao,
                               SecurityService securityService,
                               SubscriptionEvaluationService evaluationService) {
        this.subscriptionDao = subscriptionDao;
        this.securityService = securityService;
        this.evaluationService = evaluationService;
    }

    public SearchSubscription createSubscription(String username, CreateSubscriptionRequest req)
            throws SQLException {

        // Stored here and replayed against Solr on every future evaluation cycle (ResultHashService,
        // via a background job, unattended) — reject Solr local-params/magic-field syntax now so a
        // malicious subscription can never be created, not just handled defensively later.
        SolrQuerySyntaxGuard.check(req.query());

        String email = securityService.getEmailForUser(username)
                .orElseThrow(() -> new IllegalStateException(
                        "Impossible de récupérer l'adresse e-mail LDAP pour l'utilisateur : " + username));

        NotificationFrequency freq = req.frequency() != null ? req.frequency() : NotificationFrequency.IMMEDIATE;
        String sort = req.sort() != null ? req.sort() : "score";

        SearchSubscription sub = new SearchSubscription(
                UUID.randomUUID().toString(),
                username,
                email,
                req.query() != null ? req.query() : "",
                req.indices(),
                req.filename(),
                req.geoCity(),
                req.geoDpt(),
                req.geoRegion(),
                req.geoCountry(),
                req.dateOffsetFrom(),
                req.dateOffsetTo(),
                sort,
                freq,
                null,  // lastResultHash
                null,  // lastResultCount
                null,  // lastCheckedAt
                System.currentTimeMillis(),
                true
        );
        subscriptionDao.save(sub);
        evaluationService.captureInitialHash(sub);
        return sub;
    }

    public List<SearchSubscription> getSubscriptionsForUser(String username) throws SQLException {
        return subscriptionDao.findByUsername(username);
    }

    public void deleteSubscription(String id, String username) throws SQLException {
        subscriptionDao.delete(id, username);
    }

    public SearchSubscription updateFrequency(String id, String username, NotificationFrequency freq)
            throws SQLException {
        // updateFrequency() itself is already scoped by username (WHERE id = ? AND username = ?),
        // but it silently affects zero rows for an id that exists and belongs to someone else — it
        // must not fall through to an unscoped findById(id), which would then hand the caller that
        // other user's subscription (query, indices, etc.) despite having changed nothing.
        boolean updated = subscriptionDao.updateFrequency(id, username, freq);
        if (!updated) {
            throw new SQLException("Subscription not found: " + id);
        }
        return subscriptionDao.findById(id)
                .orElseThrow(() -> new SQLException("Subscription not found: " + id));
    }
}
