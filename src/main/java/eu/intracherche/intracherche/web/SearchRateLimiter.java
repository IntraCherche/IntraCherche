package eu.intracherche.intracherche.web;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Caps the searches each user can run per minute.
 *
 * <p>Every search costs Solr queries and a priority slot on the embedding service. Without a
 * cap, one user — a runaway script or a malicious one — could slow search down for everyone
 * and eat most of the embedding capacity that indexing needs. The default (60 a minute) is
 * far above what a person types, pages or re-sorts in a minute.</p>
 *
 * <p>Fixed one-minute window per user, opened by their first search: the same scheme as the
 * login throttle in {@link AuthController}.</p>
 */
@Component
public class SearchRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(SearchRateLimiter.class);
    static final long WINDOW_SECONDS = 60;

    private final int maxPerMinute;
    private final Cache<String, AtomicInteger> counts;

    @Autowired
    public SearchRateLimiter(@Value("${search.rateLimitPerMinute:60}") int maxPerMinute) {
        this(maxPerMinute, Ticker.systemTicker());
    }

    SearchRateLimiter(int maxPerMinute, Ticker ticker) {
        this.maxPerMinute = maxPerMinute;
        this.counts = Caffeine.newBuilder()
                .expireAfterWrite(WINDOW_SECONDS, TimeUnit.SECONDS)
                .ticker(ticker)
                .build();
    }

    /**
     * Counts one search for this user.
     *
     * @return false when the user is over the limit for the current window
     */
    public boolean tryAcquire(String username) {
        if (maxPerMinute <= 0) return true;
        int n = counts.get(username, k -> new AtomicInteger()).incrementAndGet();
        if (n == maxPerMinute + 1) {
            // Once per window, so a flood does not flood the log as well
            log.warn("Search rate limit reached for user {} ({} searches/minute); further searches refused until the window ends",
                    username, maxPerMinute);
        }
        return n <= maxPerMinute;
    }

    public long retryAfterSeconds() {
        return WINDOW_SECONDS;
    }
}
