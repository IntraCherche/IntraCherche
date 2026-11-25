package eu.intracherche.intracherche.sherlock;

import eu.intracherche.intracherche.config.LlmConfig;
import eu.intracherche.intracherche.config.LlmConfig.Tier;
import eu.intracherche.intracherche.registry.RegistrySchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Questions per user and per day, in three tiers from the user's AD groups. Counted in the
 * registry, as in production: an in-memory SQLite with the real schema.
 */
class SherlockQuotaTest {

    private static final String PREMIUM = "S-1-5-21-1-1301";
    private static final String EXTENDED = "S-1-5-21-1-1302";
    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");

    private LlmConfig config;
    private MutableClock clock;
    private SherlockQuota quota;

    @BeforeEach
    void setUp() throws Exception {
        Connection conn = DriverManager.getConnection("jdbc:sqlite::memory:");
        conn.setAutoCommit(false);
        RegistrySchema.ensureSchema(conn);
        config = new LlmConfig();
        config.getQuota().setBase(2);
        config.getQuota().setPremium(5);
        config.getQuota().setExtended(0);
        config.getQuota().setPremiumGroupSid(PREMIUM);
        config.getQuota().setExtendedGroupSid(EXTENDED);
        clock = new MutableClock(Instant.parse("2026-09-29T10:00:00Z"), PARIS);
        quota = new SherlockQuota(conn, config, clock);
    }

    @Test
    void a_base_user_gets_their_daily_questions_then_is_refused() throws Exception {
        assertThat(quota.consume("alice", List.of()).accepted()).isTrue();
        assertThat(quota.consume("alice", List.of()).accepted()).isTrue();

        SherlockQuota.Consumption third = quota.consume("alice", List.of());
        assertThat(third.accepted()).isFalse();
        assertThat(third.usage()).isEqualTo(new SherlockQuota.Usage(Tier.BASE, 2, 2));
        assertThat(quota.consume("bob", List.of()).accepted()).as("each user has their own count").isTrue();
    }

    @Test
    void the_highest_tier_of_the_users_groups_applies() throws Exception {
        assertThat(quota.usage("p", List.of(PREMIUM)).tier()).isEqualTo(Tier.PREMIUM);
        assertThat(quota.usage("e", List.of(PREMIUM, EXTENDED)).tier()).isEqualTo(Tier.EXTENDED);
        for (int i = 0; i < 5; i++) assertThat(quota.consume("p", List.of(PREMIUM)).accepted()).isTrue();
        assertThat(quota.consume("p", List.of(PREMIUM)).accepted()).isFalse();
    }

    @Test
    void zero_is_unlimited_and_still_counted() throws Exception {
        for (int i = 0; i < 50; i++) assertThat(quota.consume("dev", List.of(EXTENDED)).accepted()).isTrue();

        SherlockQuota.Usage usage = quota.usage("dev", List.of(EXTENDED));
        assertThat(usage.used()).isEqualTo(50);
        assertThat(usage.limit()).isZero();
        assertThat(usage.exhausted()).isFalse();
    }

    @Test
    void the_count_starts_again_at_midnight_server_time() throws Exception {
        quota.consume("alice", List.of());
        quota.consume("alice", List.of());
        clock.set(Instant.parse("2026-09-29T21:59:00Z")); // 23:59 in Paris
        assertThat(quota.consume("alice", List.of()).accepted()).isFalse();

        clock.set(Instant.parse("2026-09-29T22:00:00Z")); // midnight in Paris
        assertThat(quota.consume("alice", List.of()).accepted()).isTrue();
    }

    @Test
    void a_failed_question_is_given_back_on_the_day_it_was_counted() throws Exception {
        long askedAt = clock.millis();
        quota.consume("alice", List.of());
        quota.consume("alice", List.of());

        quota.refund("alice", askedAt);
        assertThat(quota.consume("alice", List.of()).accepted()).isTrue();

        // Failing after midnight gives back yesterday's question, not today's.
        clock.advance(Duration.ofDays(1));
        quota.consume("alice", List.of());
        quota.refund("alice", askedAt);
        assertThat(quota.usage("alice", List.of()).used()).isEqualTo(1);
    }

    @Test
    void a_refund_never_goes_below_zero() throws Exception {
        quota.refund("alice", clock.millis());
        assertThat(quota.usage("alice", List.of()).used()).isZero();
    }

    @Test
    void questions_sent_at_once_cannot_both_take_the_last_place() throws Exception {
        config.getQuota().setBase(10);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < 40; i++) results.add(pool.submit(() -> quota.consume("alice", List.of()).accepted()));
        int accepted = 0;
        for (Future<Boolean> r : results) if (r.get()) accepted++;
        pool.shutdown();

        assertThat(accepted).isEqualTo(10);
        assertThat(quota.usage("alice", List.of()).used()).isEqualTo(10);
    }

    @Test
    void the_admin_panel_lists_todays_users_busiest_first() throws Exception {
        quota.consume("alice", List.of());
        for (int i = 0; i < 3; i++) quota.consume("paul", List.of(PREMIUM));
        clock.advance(Duration.ofDays(-1));
        quota.consume("yesterday", List.of());
        clock.advance(Duration.ofDays(1));

        assertThat(quota.today(50)).containsExactly(
                new SherlockQuota.UserUsage("paul", Tier.PREMIUM, 3, 5),
                new SherlockQuota.UserUsage("alice", Tier.BASE, 1, 2));
    }

    @Test
    void the_default_is_unlimited_for_every_tier() {
        LlmConfig.Quota defaults = new LlmConfig().getQuota();
        for (Tier tier : Tier.values()) assertThat(defaults.limitOf(tier)).isZero();
        assertThat(defaults.tierOf(List.of("S-1-5-21-1-1301"))).as("no group configured").isEqualTo(Tier.BASE);
    }

    private static final class MutableClock extends Clock {
        private Instant now;
        private final ZoneId zone;

        MutableClock(Instant now, ZoneId zone) { this.now = now; this.zone = zone; }

        void set(Instant instant) { now = instant; }
        void advance(Duration d) { now = now.plus(d); }

        @Override public ZoneId getZone() { return zone; }
        @Override public Clock withZone(ZoneId zone) { return new MutableClock(now, zone); }
        @Override public Instant instant() { return now; }
    }
}
