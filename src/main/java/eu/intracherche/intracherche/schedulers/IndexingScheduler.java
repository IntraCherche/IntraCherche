package eu.intracherche.intracherche.schedulers;

import eu.intracherche.intracherche.alerts.SubscriptionEvaluationService;
import eu.intracherche.intracherche.alerts.dao.SubscriptionDao;
import eu.intracherche.intracherche.alerts.model.NotificationFrequency;
import eu.intracherche.intracherche.alerts.model.SearchSubscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class IndexingScheduler {

    private static final Logger log = LoggerFactory.getLogger(IndexingScheduler.class);

    private final SubscriptionDao subscriptionDao;
    private final SubscriptionEvaluationService evaluationService;

    public IndexingScheduler(SubscriptionDao subscriptionDao,
                             SubscriptionEvaluationService evaluationService) {
        this.subscriptionDao = subscriptionDao;
        this.evaluationService = evaluationService;
    }

    public void onIndexingCycleComplete() {
        try {
            log.info("Post-indexing: evaluating IMMEDIATE subscriptions...");
            List<SearchSubscription> subs = subscriptionDao.findActiveByFrequency(NotificationFrequency.IMMEDIATE);
            evaluationService.evaluateSubscriptions(subs);
        } catch (Exception e) {
            log.error("Error during post-indexing subscription evaluation: {}", e.getMessage(), e);
        }
    }

    @Scheduled(cron = "0 0 8 * * *")
    public void runDailyDigest() {
        try {
            log.info("Running DAILY digest...");
            List<SearchSubscription> subs = subscriptionDao.findActiveByFrequency(NotificationFrequency.DAILY);
            evaluationService.evaluateSubscriptions(subs);
        } catch (Exception e) {
            log.error("Daily digest failed: {}", e.getMessage(), e);
        }
    }

    @Scheduled(cron = "0 0 8 * * MON")
    public void runWeeklyDigest() {
        try {
            log.info("Running WEEKLY digest...");
            List<SearchSubscription> subs = subscriptionDao.findActiveByFrequency(NotificationFrequency.WEEKLY);
            evaluationService.evaluateSubscriptions(subs);
        } catch (Exception e) {
            log.error("Weekly digest failed: {}", e.getMessage(), e);
        }
    }
}
