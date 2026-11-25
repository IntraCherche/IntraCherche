package eu.intracherche.intracherche.alerts;

import eu.intracherche.intracherche.alerts.dao.SubscriptionDao;
import eu.intracherche.intracherche.alerts.model.NotificationFrequency;
import eu.intracherche.intracherche.alerts.model.SearchSubscription;
import eu.intracherche.intracherche.registry.RegistrySchema;
import eu.intracherche.intracherche.security.SecurityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

// Regression test for the subscription IDOR: PATCH .../{id}/frequency scoped its UPDATE by
// username, but unconditionally re-fetched the row unscoped afterwards to build the response —
// so a request naming someone else's subscription id changed nothing yet still received that
// other user's query, indices, etc. back in the response.
class SubscriptionServiceTest {

    private Connection conn;
    private SubscriptionService service;
    private SubscriptionDao dao;

    @BeforeEach
    void setUp() throws SQLException {
        conn = DriverManager.getConnection("jdbc:sqlite::memory:");
        conn.setAutoCommit(false);
        RegistrySchema.ensureSchema(conn);
        dao = new SubscriptionDao(conn);
        service = new SubscriptionService(dao, mock(SecurityService.class), mock(SubscriptionEvaluationService.class));

        dao.save(new SearchSubscription(
                "sub-1", "alice", "alice@corp.local", "budget municipal confidentiel", List.of("Index_1"),
                null, null, null, null, null, null, null, "score",
                NotificationFrequency.DAILY, null, null, null,
                System.currentTimeMillis(), true
        ));
    }

    @Test
    void owner_gets_the_updated_subscription_back() throws SQLException {
        SearchSubscription updated = service.updateFrequency("sub-1", "alice", NotificationFrequency.WEEKLY);

        assertThat(updated.frequency()).isEqualTo(NotificationFrequency.WEEKLY);
    }

    @Test
    void non_owner_gets_no_data_back_and_the_subscription_is_left_untouched() throws SQLException {
        assertThatThrownBy(() -> service.updateFrequency("sub-1", "mallory", NotificationFrequency.WEEKLY))
                .isInstanceOf(SQLException.class);

        // Not just "the update failed" — the query text itself must never have reached the caller.
        assertThat(dao.findById("sub-1").orElseThrow().frequency()).isEqualTo(NotificationFrequency.DAILY);
        assertThat(dao.findById("sub-1").orElseThrow().query()).isEqualTo("budget municipal confidentiel");
    }
}
