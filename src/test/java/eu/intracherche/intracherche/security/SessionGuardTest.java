package eu.intracherche.intracherche.security;

import eu.intracherche.intracherche.audit.AuditService;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * A signed, unexpired session token no longer opens a session once the user logged out, once the
 * account is deleted, disabled or expired in AD, or once its password changed — rather than
 * working until the token expires (24 h by default).
 */
class SessionGuardTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String PWD = "134050000000000000";
    private static final List<String> SIDS = List.of("S-1-5-21-1-1001", "S-1-1-0", "S-1-5-11");

    private JwtUtils jwt;
    private SecurityService directory;
    private RevokedSessions revoked;
    private AuditService audit;
    private SessionGuard guard;

    @BeforeEach
    void setUp() throws Exception {
        jwt = new JwtUtils();
        ReflectionTestUtils.setField(jwt, "jwtSecret", SECRET);
        ReflectionTestUtils.setField(jwt, "jwtExpirationMinutes", 1440);
        directory = mock(SecurityService.class);
        revoked = new RevokedSessions(registry());
        audit = mock(AuditService.class);
        guard = new SessionGuard(directory, revoked, audit);
    }

    @Test
    void an_active_account_gets_its_sids() {
        givenDirectory(active(PWD));

        UserDetails user = guard.authenticate(login(PWD), null).orElseThrow();

        assertThat(user.getUsername()).isEqualTo("alice");
        assertThat(user.getAuthorities()).extracting(GrantedAuthority::getAuthority).containsExactlyInAnyOrderElementsOf(SIDS);
    }

    @Test
    void a_logged_out_session_is_refused_even_though_its_token_is_still_valid() {
        givenDirectory(active(PWD));
        Claims session = login(PWD);

        guard.revoke(session, "déconnexion");

        assertThat(guard.authenticate(session, null)).isEmpty();
        verifyNoInteractions(directory);
    }

    @Test
    void a_disabled_account_loses_its_session_and_it_stays_revoked() {
        givenDirectory(new DirectoryUser(SIDS, true, true, true, null, PWD));
        Claims session = login(PWD);

        assertThat(guard.authenticate(session, null)).isEmpty();
        verify(audit).logSecurityEvent(any(), eq("alice"), eq("SESSION_REJECTED"), eq("compte désactivé"));

        // Re-enabling the account does not bring this session back; the user logs in again.
        givenDirectory(active(PWD));
        assertThat(guard.authenticate(session, null)).isEmpty();
        verify(audit, times(1)).logSecurityEvent(any(), any(), any(), any());
    }

    @Test
    void a_deleted_account_loses_its_session() {
        givenDirectory(DirectoryUser.notFound());

        assertThat(guard.authenticate(login(PWD), null)).isEmpty();
    }

    @Test
    void an_expired_account_loses_its_session() {
        givenDirectory(new DirectoryUser(SIDS, true, true, false, Instant.now().minusSeconds(60), PWD));

        assertThat(guard.authenticate(login(PWD), null)).isEmpty();
    }

    @Test
    void a_password_change_or_reset_after_login_ends_the_session() {
        givenDirectory(active("134060000000000000"));

        assertThat(guard.authenticate(login(PWD), null)).isEmpty();
        verify(audit).logSecurityEvent(any(), eq("alice"), eq("SESSION_REJECTED"), eq("mot de passe modifié depuis la connexion"));
    }

    @Test
    void an_unreachable_directory_refuses_nothing_but_grants_no_document() {
        givenDirectory(DirectoryUser.unreachable());

        UserDetails user = guard.authenticate(login(PWD), null).orElseThrow();

        assertThat(user.getAuthorities()).isEmpty();
        verifyNoInteractions(audit);
    }

    @Test
    void a_token_issued_before_revocation_existed_is_refused() {
        givenDirectory(active(PWD));
        Claims legacy = mock(Claims.class);
        when(legacy.getSubject()).thenReturn("alice");
        when(legacy.getId()).thenReturn(null);

        assertThat(guard.authenticate(legacy, null)).isEmpty();
    }

    @Test
    void a_revocation_survives_a_restart() throws Exception {
        Connection registry = registry();
        Claims session = login(PWD);
        new SessionGuard(directory, new RevokedSessions(registry), audit).revoke(session, "déconnexion");

        RevokedSessions afterRestart = new RevokedSessions(registry);

        assertThat(afterRestart.isRevoked(session.getId())).isTrue();
    }

    // --- fixtures ---------------------------------------------------------------

    private Claims login(String pwdLastSet) {
        String token = jwt.generateJwtToken(new UsernamePasswordAuthenticationToken(
                new User("alice", "", List.of()), null, List.of()), pwdLastSet);
        return jwt.parseClaims(token).orElseThrow();
    }

    private void givenDirectory(DirectoryUser user) {
        when(directory.lookupUser("alice")).thenReturn(user);
    }

    private static DirectoryUser active(String pwdLastSet) {
        return new DirectoryUser(SIDS, true, true, false, null, pwdLastSet);
    }

    private static Connection registry() throws Exception {
        Connection conn = DriverManager.getConnection("jdbc:sqlite::memory:");
        conn.setAutoCommit(false);
        eu.intracherche.intracherche.registry.RegistrySchema.ensureSchema(conn);
        return conn;
    }
}
