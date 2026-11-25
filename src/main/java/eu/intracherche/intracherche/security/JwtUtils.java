package eu.intracherche.intracherche.security;

import eu.intracherche.intracherche.app.ApplicationOrchestrator;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

@Component
public class JwtUtils {
    private static final Logger log = LoggerFactory.getLogger(JwtUtils.class);

    @Value("${jwt.secret}")
    private String jwtSecret;

    /**
     * How long a session lasts, in minutes (1440 = 24 h).
     *
     * <p>The single place this duration is defined. The login cookie's max-age is derived from
     * it through {@link #getSessionDuration()} rather than written out a second time: a cookie
     * that outlives its token leaves the browser holding a session the server has already
     * rejected, and one that dies first logs the user out while the token is still valid.
     */
    @Value("${jwt.expirationMinutes:1440}")
    private int jwtExpirationMinutes;

    @PostConstruct
    void validateExpiration() {
        if (jwtExpirationMinutes < 1) {
            throw new IllegalStateException(
                    "jwt.expirationMinutes must be at least 1; got " + jwtExpirationMinutes
                    + ". A session that expires on issue makes every login fail.");
        }
        log.info("Session lifetime: {} minutes", jwtExpirationMinutes);
    }

    /** The session lifetime, for the token and the cookie alike. */
    public Duration getSessionDuration() {
        return Duration.ofMinutes(jwtExpirationMinutes);
    }

    // Helper to turn your String into a proper SecretKey object
    private SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
    }

    /** Claim holding the account's pwdLastSet as read at login (see {@link DirectoryUser#pwdLastSet()}). */
    public static final String PWD_LAST_SET_CLAIM = "pwd";

    public String generateJwtToken(Authentication authentication) {
        return generateJwtToken(authentication, null);
    }

    /**
     * A session token with its own id (jti), so this one session can be revoked on logout, and the
     * account's pwdLastSet at login, so a later password change or reset ends it.
     */
    public String generateJwtToken(Authentication authentication, String pwdLastSet) {
        UserDetails userPrincipal = (UserDetails) authentication.getPrincipal();

        var builder = Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(userPrincipal.getUsername())
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + getSessionDuration().toMillis()));
        if (pwdLastSet != null) builder.claim(PWD_LAST_SET_CLAIM, pwdLastSet);
        return builder.signWith(getSigningKey()).compact();
    }

    /** The claims of a correctly signed, unexpired token; empty otherwise (the reason is logged). */
    public Optional<Claims> parseClaims(String authToken) {
        try {
            return Optional.of(Jwts.parser()
                    .verifyWith(getSigningKey())
                    .build()
                    .parseSignedClaims(authToken)
                    .getPayload());
        } catch (io.jsonwebtoken.security.SignatureException e) {
            log.error("Invalid JWT signature: {}", e.getMessage());
        } catch (io.jsonwebtoken.ExpiredJwtException e) {
            log.error("JWT token is expired: {}", e.getMessage());
        } catch (io.jsonwebtoken.UnsupportedJwtException e) {
            log.error("JWT token is unsupported: {}", e.getMessage());
        } catch (io.jsonwebtoken.MalformedJwtException e) {
            log.error("Invalid JWT token: {}", e.getMessage());
        } catch (IllegalArgumentException e) {
            log.error("JWT claims string is empty: {}", e.getMessage());
        }
        return Optional.empty();
    }

    public boolean validateJwtToken(String authToken) {
        return parseClaims(authToken).isPresent();
    }

    public String getUserNameFromJwtToken(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey()) // Replaces setSigningKey
                .build()
                .parseSignedClaims(token)    // Replaces parseClaimsJws
                .getPayload()                // Replaces getBody
                .getSubject();
    }
}