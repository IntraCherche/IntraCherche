package eu.intracherche.intracherche.security;

import eu.intracherche.intracherche.audit.AuditService;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Decides whether a correctly signed, unexpired session token still opens a session.
 *
 * <p>A signature only proves the token was issued; it says nothing of what happened since. The
 * session ends before the token expires when:
 * <ul>
 *   <li>it was revoked — the user logged out, or it was already refused for one of the reasons below;</li>
 *   <li>the account was deleted, disabled or has expired in the directory;</li>
 *   <li>the account's password changed since login (changed by the user, reset or set to change at
 *       next logon by an administrator).</li>
 * </ul>
 * The directory state comes from {@link SecurityService#lookupUser}, cached with the SIDs, so it
 * takes effect within security.sidCacheTtlMinutes. When the directory cannot be read, nothing is
 * refused on that basis: the session goes on with no SIDs, i.e. no document, as before.
 *
 * <p>A token without an id predates revocation and cannot be revoked, so it is refused: the user
 * logs in again once after the upgrade.
 */
@Component
public class SessionGuard {

    private static final Logger log = LoggerFactory.getLogger(SessionGuard.class);

    private final SecurityService securityService;
    private final RevokedSessions revokedSessions;
    private final AuditService auditService;
    private final Clock clock;

    @Autowired
    public SessionGuard(SecurityService securityService, RevokedSessions revokedSessions, AuditService auditService) {
        this(securityService, revokedSessions, auditService, Clock.systemUTC());
    }

    SessionGuard(SecurityService securityService, RevokedSessions revokedSessions,
                 AuditService auditService, Clock clock) {
        this.securityService = securityService;
        this.revokedSessions = revokedSessions;
        this.auditService = auditService;
        this.clock = clock;
    }

    /** The user this token authenticates, with their SIDs as authorities; empty if the session is over. */
    public Optional<UserDetails> authenticate(Claims claims, HttpServletRequest request) {
        String username = claims.getSubject();
        String jti = claims.getId();
        if (username == null || username.isBlank()) return Optional.empty();

        if (jti == null) {
            auditService.logSecurityEvent(request, username, "SESSION_REJECTED",
                    "session ouverte avant la mise à jour : reconnexion demandée");
            return Optional.empty();
        }
        if (revokedSessions.isRevoked(jti)) {
            log.debug("Revoked session presented for '{}'", username);
            return Optional.empty();
        }

        DirectoryUser user = securityService.lookupUser(username);
        Optional<String> refusal = user.denialReason(clock.instant())
                .or(() -> passwordChanged(claims, user));
        if (refusal.isPresent()) {
            // Revoked so that the next requests carrying this token are refused without a
            // directory read, and without a new audit line each.
            revokedSessions.revoke(jti, username, expiry(claims), refusal.get());
            auditService.logSecurityEvent(request, username, "SESSION_REJECTED", refusal.get());
            return Optional.empty();
        }

        List<SimpleGrantedAuthority> authorities = (user.reachable() ? user.sids() : List.<String>of()).stream()
                .map(SimpleGrantedAuthority::new)
                .toList();
        return Optional.of(new User(username, "", authorities));
    }

    /** Ends this session now: the logout. */
    public void revoke(Claims claims, String reason) {
        if (claims.getId() != null) {
            revokedSessions.revoke(claims.getId(), claims.getSubject(), expiry(claims), reason);
        }
    }

    private static Optional<String> passwordChanged(Claims claims, DirectoryUser user) {
        String atLogin = claims.get(JwtUtils.PWD_LAST_SET_CLAIM, String.class);
        if (atLogin == null || !user.reachable() || user.pwdLastSet() == null) return Optional.empty();
        return Objects.equals(atLogin, user.pwdLastSet())
                ? Optional.empty()
                : Optional.of("mot de passe modifié depuis la connexion");
    }

    private Instant expiry(Claims claims) {
        return claims.getExpiration() != null ? claims.getExpiration().toInstant() : clock.instant();
    }
}
