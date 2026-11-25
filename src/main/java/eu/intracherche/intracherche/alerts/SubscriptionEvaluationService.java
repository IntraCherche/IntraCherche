package eu.intracherche.intracherche.alerts;

import eu.intracherche.intracherche.alerts.dao.NotificationDao;
import eu.intracherche.intracherche.alerts.dao.SubscriptionDao;
import eu.intracherche.intracherche.alerts.model.AppNotification;
import eu.intracherche.intracherche.alerts.model.SearchSubscription;
import eu.intracherche.intracherche.security.SecurityService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class SubscriptionEvaluationService {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionEvaluationService.class);

    private final SubscriptionDao subscriptionDao;
    private final NotificationDao notificationDao;
    private final ResultHashService resultHashService;
    private final SecurityService securityService;
    private final AlertEmailService emailService;

    public SubscriptionEvaluationService(SubscriptionDao subscriptionDao,
                                         NotificationDao notificationDao,
                                         ResultHashService resultHashService,
                                         SecurityService securityService,
                                         AlertEmailService emailService) {
        this.subscriptionDao = subscriptionDao;
        this.notificationDao = notificationDao;
        this.resultHashService = resultHashService;
        this.securityService = securityService;
        this.emailService = emailService;
    }

    public void captureInitialHash(SearchSubscription sub) {
        try {
            List<String> sids = securityService.getEffectiveSids(sub.username());
            Optional<HashAndCount> result = resultHashService.computeHash(sub, sids);
            if (result.isEmpty()) {
                log.debug("Skipped initial hash for subscription {} — cores not yet ready", sub.id());
                return;
            }
            HashAndCount hac = result.get();
            subscriptionDao.updateResultHash(sub.id(), hac.hash(), hac.count(), System.currentTimeMillis());
            log.debug("Captured initial hash for subscription {} (user={}, count={})", sub.id(), sub.username(), hac.count());
        } catch (Exception e) {
            log.warn("Could not capture initial hash for subscription {} — will be set on first check: {}",
                    sub.id(), e.getMessage());
        }
    }

    public void evaluateSubscriptions(List<SearchSubscription> subscriptions) {
        for (SearchSubscription sub : subscriptions) {
            try {
                evaluateOne(sub);
            } catch (Exception e) {
                log.error("Error evaluating subscription {} (user={}): {}",
                        sub.id(), sub.username(), e.getMessage(), e);
            }
        }
    }

    private void evaluateOne(SearchSubscription sub) throws Exception {
        List<String> sids = securityService.getEffectiveSids(sub.username());
        Optional<HashAndCount> result = resultHashService.computeHash(sub, sids);
        if (result.isEmpty()) return;
        HashAndCount hac = result.get();
        long now = System.currentTimeMillis();

        String oldHash = sub.lastResultHash();

        if (oldHash != null && oldHash.equals(hac.hash())) {
            subscriptionDao.updateResultHash(sub.id(), hac.hash(), hac.count(), now);
            log.debug("No change for subscription {} (user={})", sub.id(), sub.username());
            return;
        }

        if (oldHash == null) {
            subscriptionDao.updateResultHash(sub.id(), hac.hash(), hac.count(), now);
            log.debug("Initial hash set for subscription {} — no notification", sub.id());
            return;
        }

        String rawQuery = (sub.query() == null) ? "" : sub.query();
        String displayLabel = rawQuery.isBlank() ? "(tous les documents)" : rawQuery;

        if (!notificationDao.hasUnreadForSubscription(sub.id())) {
            String message = hac.count() + " nouveaux résultats disponibles pour votre recherche « " + displayLabel + " ».";
            notificationDao.save(AppNotification.forSearchAlert(
                    UUID.randomUUID().toString(),
                    sub.username(),
                    sub.id(),
                    message,
                    rawQuery,
                    now
            ));
            notificationDao.pruneOld(sub.username(), 100);
        }

        // Hash is updated only after a successful send. If the send throws, the hash stays
        // at oldHash so the next evaluation cycle retries the email.
        emailService.sendAlertEmail(sub.userEmail(), sub.username(), displayLabel, hac.count());
        subscriptionDao.updateResultHash(sub.id(), hac.hash(), hac.count(), now);
        log.info("New results detected for subscription {} (user={}) — {} results",
                sub.id(), sub.username(), hac.count());
    }
}
