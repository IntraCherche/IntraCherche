package eu.intracherche.intracherche.alerts;

import eu.intracherche.intracherche.alerts.dao.NotificationDao;
import eu.intracherche.intracherche.alerts.dao.SubscriptionDao;
import eu.intracherche.intracherche.alerts.model.AppNotification;
import eu.intracherche.intracherche.alerts.model.NotificationFrequency;
import eu.intracherche.intracherche.alerts.model.SearchSubscription;
import eu.intracherche.intracherche.alerts.HashAndCount;
import eu.intracherche.intracherche.security.SecurityService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SubscriptionEvaluationServiceTest {

    @Mock SubscriptionDao subscriptionDao;
    @Mock NotificationDao notificationDao;
    @Mock ResultHashService resultHashService;
    @Mock SecurityService securityService;
    @Mock AlertEmailService emailService;

    @InjectMocks SubscriptionEvaluationService service;

    private static final List<String> SIDS = List.of("S-1-1-0", "S-1-5-11");

    private SearchSubscription sub(String id, String username, String email, String query, String lastHash) {
        return new SearchSubscription(
                id, username, email,
                query, List.of("idx-docs"),
                null, null, null, null, null,
                null, null, null, NotificationFrequency.IMMEDIATE,
                lastHash, null, null, System.currentTimeMillis(), true
        );
    }

    @Test
    void initial_hash_is_stored_without_triggering_notification() throws Exception {
        SearchSubscription sub = sub("s1", "alice", "alice@corp.local", "budget", null);
        when(securityService.getEffectiveSids("alice")).thenReturn(SIDS);
        when(resultHashService.computeHash(eq(sub), eq(SIDS))).thenReturn(Optional.of(new HashAndCount("hash-abc", 0)));

        service.evaluateSubscriptions(List.of(sub));

        verify(subscriptionDao).updateResultHash(eq("s1"), eq("hash-abc"), anyInt(), anyLong());
        verify(notificationDao, never()).save(any());
        verify(emailService, never()).sendAlertEmail(any(), any(), any(), anyInt());
    }

    @Test
    void unchanged_hash_produces_no_notification() throws Exception {
        SearchSubscription sub = sub("s1", "alice", "alice@corp.local", "budget", "hash-abc");
        when(securityService.getEffectiveSids("alice")).thenReturn(SIDS);
        when(resultHashService.computeHash(eq(sub), eq(SIDS))).thenReturn(Optional.of(new HashAndCount("hash-abc", 0)));

        service.evaluateSubscriptions(List.of(sub));

        verify(subscriptionDao).updateResultHash(eq("s1"), eq("hash-abc"), anyInt(), anyLong());
        verify(notificationDao, never()).save(any());
        verify(emailService, never()).sendAlertEmail(any(), any(), any(), anyInt());
    }

    @Test
    void changed_hash_saves_notification_and_sends_email() throws Exception {
        SearchSubscription sub = sub("s1", "alice", "alice@corp.local", "budget report", "hash-old");
        when(securityService.getEffectiveSids("alice")).thenReturn(SIDS);
        when(resultHashService.computeHash(eq(sub), eq(SIDS))).thenReturn(Optional.of(new HashAndCount("hash-new", 3)));

        service.evaluateSubscriptions(List.of(sub));

        ArgumentCaptor<AppNotification> notif = ArgumentCaptor.forClass(AppNotification.class);
        verify(notificationDao).save(notif.capture());
        assertThat(notif.getValue().username()).isEqualTo("alice");
        assertThat(notif.getValue().subscriptionId()).isEqualTo("s1");
        assertThat(notif.getValue().querySummary()).isEqualTo("budget report");
        verify(emailService).sendAlertEmail("alice@corp.local", "alice", "budget report", 3);
    }

    @Test
    void error_in_one_subscription_does_not_block_the_next() throws Exception {
        SearchSubscription failing = sub("s1", "alice", "alice@corp.local", "budget", "hash-old");
        SearchSubscription ok     = sub("s2", "bob",   "bob@corp.local",   "inventory", "hash-old");

        when(securityService.getEffectiveSids("alice")).thenThrow(new RuntimeException("LDAP unreachable"));
        when(securityService.getEffectiveSids("bob")).thenReturn(SIDS);
        when(resultHashService.computeHash(eq(ok), eq(SIDS))).thenReturn(Optional.of(new HashAndCount("hash-new", 1)));

        service.evaluateSubscriptions(List.of(failing, ok));

        verify(notificationDao).save(argThat(n -> "bob".equals(n.username())));
        verify(notificationDao, never()).save(argThat(n -> "alice".equals(n.username())));
    }
}
