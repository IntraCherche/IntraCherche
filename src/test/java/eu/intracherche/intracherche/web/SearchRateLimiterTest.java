package eu.intracherche.intracherche.web;

import com.github.benmanes.caffeine.cache.Ticker;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class SearchRateLimiterTest {

    private final AtomicLong nanos = new AtomicLong();
    private final Ticker ticker = nanos::get;

    private void advanceSeconds(long seconds) {
        nanos.addAndGet(TimeUnit.SECONDS.toNanos(seconds));
    }

    @Test
    void refuses_searches_past_the_limit_within_the_minute() {
        SearchRateLimiter limiter = new SearchRateLimiter(3, ticker);

        assertThat(limiter.tryAcquire("alice")).isTrue();
        assertThat(limiter.tryAcquire("alice")).isTrue();
        assertThat(limiter.tryAcquire("alice")).isTrue();
        assertThat(limiter.tryAcquire("alice")).isFalse();
        assertThat(limiter.tryAcquire("alice")).isFalse();
    }

    @Test
    void one_user_over_the_limit_does_not_block_another() {
        SearchRateLimiter limiter = new SearchRateLimiter(1, ticker);

        assertThat(limiter.tryAcquire("mallory")).isTrue();
        assertThat(limiter.tryAcquire("mallory")).isFalse();
        assertThat(limiter.tryAcquire("alice")).isTrue();
    }

    @Test
    void allowance_comes_back_once_the_window_is_over() {
        SearchRateLimiter limiter = new SearchRateLimiter(2, ticker);
        limiter.tryAcquire("alice");
        limiter.tryAcquire("alice");
        assertThat(limiter.tryAcquire("alice")).isFalse();

        advanceSeconds(SearchRateLimiter.WINDOW_SECONDS + 1);

        assertThat(limiter.tryAcquire("alice")).isTrue();
    }

    @Test
    void zero_disables_the_limit() {
        SearchRateLimiter limiter = new SearchRateLimiter(0, ticker);
        for (int i = 0; i < 1000; i++) assertThat(limiter.tryAcquire("alice")).isTrue();
    }
}
