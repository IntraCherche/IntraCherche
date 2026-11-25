package eu.intracherche.intracherche.alerts.dao;

import eu.intracherche.intracherche.alerts.model.NotificationFrequency;
import eu.intracherche.intracherche.alerts.model.SearchSubscription;
import eu.intracherche.intracherche.registry.RegistrySchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SubscriptionDaoTest {

    private Connection conn;
    private SubscriptionDao dao;

    @BeforeEach
    void setUp() throws SQLException {
        conn = DriverManager.getConnection("jdbc:sqlite::memory:");
        conn.setAutoCommit(false);
        RegistrySchema.ensureSchema(conn);
        dao = new SubscriptionDao(conn);
    }

    private SearchSubscription alicesSubscription() {
        return new SearchSubscription(
                "sub-1", "alice", "alice@corp.local", "budget municipal", List.of("Index_1"),
                null, null, null, null, null, null, null, "score",
                NotificationFrequency.DAILY, null, null, null,
                System.currentTimeMillis(), true
        );
    }

    @Test
    void owner_can_update_their_own_subscription_frequency() throws SQLException {
        dao.save(alicesSubscription());

        boolean updated = dao.updateFrequency("sub-1", "alice", NotificationFrequency.WEEKLY);

        assertThat(updated).isTrue();
        assertThat(dao.findById("sub-1").orElseThrow().frequency()).isEqualTo(NotificationFrequency.WEEKLY);
    }

    // Regression test for the IDOR: a non-owner's request must not just no-op the write, it must
    // be told apart from success by its caller (SubscriptionService), which otherwise falls
    // through to an unscoped findById() and hands the requester someone else's subscription.
    @Test
    void non_owner_update_reports_no_row_touched_and_leaves_the_subscription_unchanged() throws SQLException {
        dao.save(alicesSubscription());

        boolean updated = dao.updateFrequency("sub-1", "mallory", NotificationFrequency.WEEKLY);

        assertThat(updated).isFalse();
        assertThat(dao.findById("sub-1").orElseThrow().frequency()).isEqualTo(NotificationFrequency.DAILY);
    }

    @Test
    void update_of_an_unknown_id_reports_no_row_touched() throws SQLException {
        boolean updated = dao.updateFrequency("does-not-exist", "alice", NotificationFrequency.WEEKLY);

        assertThat(updated).isFalse();
    }
}
