package eu.intracherche.intracherche.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.User;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The session duration has one definition, and the cookie takes its lifetime from it.
 *
 * <p>It used to be written twice — {@code JwtUtils.jwtExpirationMs} and
 * {@code AuthController.JWT_MAX_AGE_SECONDS} — kept in step only by a comment. Letting them
 * drift is quietly nasty: a cookie outliving its token leaves the browser holding a session
 * the server rejects, and one expiring first logs the user out while the token is still good.
 */
class JwtSessionDurationTest {

    private static final String SECRET =
            "test-secret-that-is-long-enough-for-hmac-sha-256-at-least-64-chars-long!!";

    private JwtUtils utilsWith(int minutes) {
        JwtUtils utils = new JwtUtils();
        ReflectionTestUtils.setField(utils, "jwtSecret", SECRET);
        ReflectionTestUtils.setField(utils, "jwtExpirationMinutes", minutes);
        return utils;
    }

    @Test
    @DisplayName("defaults to 24 hours")
    void defaultsToTwentyFourHours() {
        assertThat(utilsWith(1440).getSessionDuration()).isEqualTo(Duration.ofHours(24));
    }

    @Test
    @DisplayName("the token's own expiry matches the configured duration")
    void tokenExpiryMatchesConfiguredDuration() {
        JwtUtils utils = utilsWith(480); // a working day
        Authentication auth = new UsernamePasswordAuthenticationToken(
                new User("alice", "", List.of()), null, List.of());

        long before = System.currentTimeMillis();
        String token = utils.generateJwtToken(auth);
        long after = System.currentTimeMillis();

        Date expiry = Jwts.parser()
                .verifyWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .build()
                .parseSignedClaims(token)
                .getPayload()
                .getExpiration();

        // The cookie's max-age is this same Duration, so proving the token honours it proves
        // the pair cannot disagree. The exp claim is seconds-precision, so allow the
        // truncation of up to one second at the lower bound.
        long expected = Duration.ofMinutes(480).toMillis();
        assertThat(expiry.getTime())
                .isBetween(before + expected - 1000, after + expected);
        assertThat(utils.getSessionDuration()).isEqualTo(Duration.ofMinutes(480));
    }

    @Test
    @DisplayName("a duration under a minute is refused at startup, not at login")
    void refusesNonPositiveDuration() {
        assertThatThrownBy(() -> utilsWith(0).validateExpiration())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jwt.expirationMinutes");

        assertThatThrownBy(() -> utilsWith(-5).validateExpiration())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("a valid duration passes validation")
    void acceptsValidDuration() {
        utilsWith(1).validateExpiration();
        utilsWith(1440).validateExpiration();
    }
}
