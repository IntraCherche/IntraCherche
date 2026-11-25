package eu.intracherche.intracherche.schedulers;

import eu.intracherche.intracherche.alerts.SubscriptionEvaluationService;
import eu.intracherche.intracherche.alerts.dao.SubscriptionDao;
import eu.intracherche.intracherche.alerts.model.NotificationFrequency;
import eu.intracherche.intracherche.alerts.model.SearchSubscription;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IndexingSchedulerTest {

    @Mock SubscriptionDao subscriptionDao;
    @Mock SubscriptionEvaluationService evaluationService;

    @InjectMocks IndexingScheduler scheduler;

    @Test
    void on_cycle_complete_evaluates_only_immediate_subscriptions() throws Exception {
        List<SearchSubscription> subs = List.of(
                new SearchSubscription("s1", "alice", "alice@corp.local",
                        "budget", List.of("idx-docs"),
                        null, null, null, null, null,
                        null, null, null, NotificationFrequency.IMMEDIATE,
                        null, null, null, System.currentTimeMillis(), true)
        );
        when(subscriptionDao.findActiveByFrequency(NotificationFrequency.IMMEDIATE)).thenReturn(subs);

        scheduler.onIndexingCycleComplete();

        verify(subscriptionDao).findActiveByFrequency(NotificationFrequency.IMMEDIATE);
        verify(evaluationService).evaluateSubscriptions(subs);
        // DAILY and WEEKLY subscriptions must not be evaluated here
        verify(subscriptionDao, never()).findActiveByFrequency(NotificationFrequency.DAILY);
        verify(subscriptionDao, never()).findActiveByFrequency(NotificationFrequency.WEEKLY);
    }
}
