package eu.intracherche.intracherche.security;

import eu.intracherche.intracherche.audit.AuditService;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.URL;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The demo's Google login: who may sign in, and as whom. The properties that matter are that every
 * visitor gets the demo account's SIDs and nothing else, that two visitors never share a session
 * name (the quota and the question history hang on it), and that every doubtful identity is refused.
 */
class OidcLoginTest {

    private static final String GOOGLE = "https://accounts.google.com";
    private static final String SECRET = "a-test-secret-that-is-long-enough-for-hmac-sha-256-signing-0123456789";
    private static final String IDP = "https://idp.example/realms/corp";
    private static final List<String> DEMO_SIDS = List.of("S-1-5-21-1-2-3-1105", "S-1-1-0", "S-1-5-11");

    private SecurityService securityService;
    private AuditService audit;
    private JwtUtils jwtUtils;

    @BeforeEach
    void setUp() {
        securityService = mock(SecurityService.class);
        audit = mock(AuditService.class);
        jwtUtils = new JwtUtils();
        ReflectionTestUtils.setField(jwtUtils, "jwtSecret", SECRET);
        ReflectionTestUtils.setField(jwtUtils, "jwtExpirationMinutes", 60);

        when(securityService.lookupUser("demo")).thenReturn(
                new DirectoryUser(DEMO_SIDS, true, true, false, null, "134000000000000000"));
    }

    private OidcLogin login(boolean enabled) {
        return new OidcLogin(enabled, "demo-fixed", "google", "demo", GOOGLE, "email", "mail", "", "", true,
                SECRET, securityService, jwtUtils, new SessionCookies(true), audit);
    }

    private static Authentication google(String subject, Boolean emailVerified, String issuer) throws Exception {
        OidcUser user = mock(OidcUser.class);
        when(user.getIssuer()).thenReturn(issuer == null ? null : new URL(issuer));
        when(user.getSubject()).thenReturn(subject);
        when(user.getEmailVerified()).thenReturn(emailVerified);
        Authentication auth = mock(Authentication.class);
        when(auth.getPrincipal()).thenReturn(user);
        return auth;
    }

    /** No session was opened: the only cookies sent are the ones that END something (the old session, the handshake). */
    private static void assertNoSession(MockHttpServletResponse response) {
        for (String cookie : response.getHeaders("Set-Cookie")) {
            assertThat(cookie).as("a refusal clears cookies, it never sets a session").contains("Max-Age=0");
            assertThat(cookie).matches("(__Host-jwt|JSESSIONID)=;.*");
        }
    }

    @Test
    @DisplayName("a refused login also ends the session that was open, so the previous user does not stay signed in")
    void refusalClearsThePreviousSession() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        login(true).onAuthenticationSuccess(new MockHttpServletRequest(), response, google("111", false, GOOGLE));

        assertThat(response.getHeaders("Set-Cookie")).anyMatch(c -> c.startsWith("__Host-jwt=;") && c.contains("Max-Age=0"));
        assertThat(response.getRedirectedUrl()).isEqualTo(OidcLogin.FAILURE_URL);
    }

    @Test
    @DisplayName("the handshake session cookie is expired once the login ends, success or refusal, so no dead cookie stays")
    void handshakeCookieIsExpired() throws Exception {
        MockHttpServletResponse ok = new MockHttpServletResponse();
        login(true).onAuthenticationSuccess(new MockHttpServletRequest(), ok, google("111", true, GOOGLE));
        MockHttpServletResponse refused = new MockHttpServletResponse();
        login(true).onAuthenticationSuccess(new MockHttpServletRequest(), refused, google("111", false, GOOGLE));
        MockHttpServletResponse failed = new MockHttpServletResponse();
        login(true).onAuthenticationFailure(new MockHttpServletRequest(), failed,
                new org.springframework.security.authentication.BadCredentialsException("state mismatch"));

        for (MockHttpServletResponse response : java.util.List.of(ok, refused, failed)) {
            assertThat(response.getHeaders("Set-Cookie")).anyMatch(c -> c.startsWith("JSESSIONID=;")
                    && c.contains("Max-Age=0") && c.contains("Path=/") && c.contains("Secure")
                    && c.contains("HttpOnly") && c.contains("SameSite=Lax"));
        }
    }

    @Test
    @DisplayName("a successful login keeps the anti-CSRF cookie that the same response carries")
    void successKeepsOtherCookies() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.addHeader("Set-Cookie", "XSRF-TOKEN=abc; Path=/; SameSite=Strict");   // added earlier by the CSRF filter
        login(true).onAuthenticationSuccess(new MockHttpServletRequest(), response, google("111", true, GOOGLE));

        assertThat(response.getHeaders("Set-Cookie")).anyMatch(c -> c.startsWith("XSRF-TOKEN=abc"));
        assertThat(response.getHeaders("Set-Cookie")).anyMatch(c -> c.startsWith("__Host-jwt=") && !c.startsWith("__Host-jwt=;"));
    }

    private Claims sessionOf(MockHttpServletResponse response) {
        String cookie = response.getHeaders("Set-Cookie").stream()
                .filter(c -> c.startsWith("__Host-jwt=") && !c.startsWith("__Host-jwt=;")).findFirst().orElse(null);
        assertThat(cookie).as("a session cookie was issued").isNotNull();
        String token = cookie.substring("__Host-jwt=".length(), cookie.indexOf(';'));
        return jwtUtils.parseClaims(token).orElseThrow();
    }

    @Test
    @DisplayName("a verified Google visitor gets a session named after the demo account and a visitor id")
    void verifiedVisitorSignsIn() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        login(true).onAuthenticationSuccess(new MockHttpServletRequest(), response,
                google("110169484474386276334", true, GOOGLE));

        Claims claims = sessionOf(response);
        assertThat(claims.getSubject()).startsWith("demo|").hasSize("demo|".length() + 16);
        assertThat(DirectoryNames.of(claims.getSubject())).isEqualTo("demo");
        assertThat(claims.getId()).isNotBlank();
        assertThat(claims.get(JwtUtils.PWD_LAST_SET_CLAIM, String.class)).isEqualTo("134000000000000000");
        assertThat(response.getRedirectedUrl()).isEqualTo("/");
        verify(audit).logSecurityEvent(any(), eq(claims.getSubject()), eq("LOGIN"), any());
    }

    @Test
    @DisplayName("the visitor's SIDs are the demo account's own, nothing is added from the token")
    void sidsAreTheDemoAccountsOnly() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        login(true).onAuthenticationSuccess(new MockHttpServletRequest(), response,
                google("110169484474386276334", true, GOOGLE));
        sessionOf(response);

        // Only the demo account is ever read from the directory — never another account.
        verify(securityService).lookupUser("demo");
        verify(securityService, never()).lookupUser(eq("alice"));
        verify(securityService, never()).getEffectiveSids(any());
    }

    @Test
    @DisplayName("two visitors get two session names, and the same visitor always gets the same one")
    void visitorsAreToldApart() throws Exception {
        OidcLogin login = login(true);
        String a1 = visitorSession(login, "111");
        String a2 = visitorSession(login, "111");
        String b = visitorSession(login, "222");

        assertThat(a1).isEqualTo(a2);
        assertThat(a1).isNotEqualTo(b);
    }

    private String visitorSession(OidcLogin login, String subject) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        login.onAuthenticationSuccess(new MockHttpServletRequest(), response, google(subject, true, GOOGLE));
        return sessionOf(response).getSubject();
    }

    @Test
    @DisplayName("the visitor id does not contain the Google subject")
    void visitorIdHidesTheSubject() throws Exception {
        assertThat(visitorSession(login(true), "110169484474386276334")).doesNotContain("110169484474386276334");
    }

    @Test
    @DisplayName("an unverified e-mail address is refused")
    void unverifiedEmailRefused() throws Exception {
        assertRefused(google("111", false, GOOGLE));
        assertRefused(google("111", null, GOOGLE));
    }

    @Test
    @DisplayName("an identity from another issuer is refused")
    void otherIssuerRefused() throws Exception {
        assertRefused(google("111", true, "https://evil.example.com"));
        assertRefused(google("111", true, null));
    }

    @Test
    @DisplayName("an identity without a subject is refused")
    void noSubjectRefused() throws Exception {
        assertRefused(google("", true, GOOGLE));
        assertRefused(google(null, true, GOOGLE));
    }

    @Test
    @DisplayName("nothing signs in when the login is switched off")
    void disabledRefused() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        login(false).onAuthenticationSuccess(new MockHttpServletRequest(), response, google("111", true, GOOGLE));
        assertNoSession(response);
        assertThat(response.getRedirectedUrl()).isEqualTo(OidcLogin.FAILURE_URL);
    }

    @Test
    @DisplayName("a disabled or missing demo account opens no session")
    void unusableAccountRefused() throws Exception {
        when(securityService.lookupUser("demo")).thenReturn(
                new DirectoryUser(List.of(), true, true, true, null, null));
        assertRefused(google("111", true, GOOGLE));

        when(securityService.lookupUser("demo")).thenReturn(DirectoryUser.unreachable());
        assertRefused(google("111", true, GOOGLE));
    }

    @Test
    @DisplayName("a failed handshake sends the visitor back to the login page and is audited")
    void failureRedirects() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        login(true).onAuthenticationFailure(new MockHttpServletRequest(), response,
                new org.springframework.security.authentication.BadCredentialsException("state mismatch"));
        assertNoSession(response);
        assertThat(response.getRedirectedUrl()).isEqualTo(OidcLogin.FAILURE_URL);
        verify(audit).logSecurityEvent(any(), any(), eq("LOGIN_FAILED"), any());
    }

    @Test
    @DisplayName("the demo password login gets a visitor session per client address, stable across logins")
    void passwordVisitorSessionIsPerAddress() {
        OidcLogin login = login(true);
        String first = login.passwordVisitorSession("203.0.113.7");

        assertThat(first).startsWith("demo|");
        assertThat(login.passwordVisitorSession("203.0.113.7")).isEqualTo(first);
        assertThat(login.passwordVisitorSession("203.0.113.8")).isNotEqualTo(first);
        assertThat(first).doesNotContain("203.0.113.7");
        assertThat(DirectoryNames.of(first)).isEqualTo("demo");
    }

    @Test
    @DisplayName("only the fixed account gets a visitor session, whatever the case, and only when the login is on")
    void fixedUserDetection() {
        assertThat(login(true).isFixedUser("demo")).isTrue();
        assertThat(login(true).isFixedUser("Demo")).isTrue();
        assertThat(login(true).isFixedUser("alice")).isFalse();
        assertThat(login(false).isFixedUser("demo")).isFalse();
    }

    @Test
    @DisplayName("a fixed account name containing a bar is rejected at startup")
    void barInAccountRejected() {
        OidcLogin bad = new OidcLogin(true, "demo-fixed", "google", "demo|x", GOOGLE, "email", "mail", "", "", true,
                "s", securityService, jwtUtils, new SessionCookies(true), audit);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, bad::validate);
    }

    private void assertRefused(Authentication authentication) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        login(true).onAuthenticationSuccess(new MockHttpServletRequest(), response, authentication);
        assertNoSession(response);
        assertThat(response.getRedirectedUrl()).isEqualTo(OidcLogin.FAILURE_URL);
    }

    // ── Directory mode: the provider's identity becomes one directory account ─────────────────────────

    private static final List<String> JEAN_SIDS = List.of("S-1-5-21-1-2-3-2001", "S-1-1-0", "S-1-5-11");

    private OidcLogin directory(String claim, String attribute, String requireVerified) {
        return new OidcLogin(true, "directory", "sso", "demo", IDP, claim, attribute, requireVerified, "", false,
                SECRET, securityService, jwtUtils, new SessionCookies(true), audit);
    }

    private static Authentication idp(String issuer, Boolean emailVerified, String claim, String value) throws Exception {
        OidcUser user = mock(OidcUser.class);
        when(user.getIssuer()).thenReturn(issuer == null ? null : new URL(issuer));
        when(user.getSubject()).thenReturn("f5a1-sub");
        when(user.getEmailVerified()).thenReturn(emailVerified);
        when(user.getClaimAsString(claim)).thenReturn(value);
        Authentication auth = mock(Authentication.class);
        when(auth.getPrincipal()).thenReturn(user);
        return auth;
    }

    private void givenAccounts(String attribute, String value, SecurityService.AccountMatch match) {
        when(securityService.findAccounts(attribute, value)).thenReturn(match);
    }

    private void givenJean() {
        when(securityService.lookupUser("jdupont")).thenReturn(
                new DirectoryUser(JEAN_SIDS, true, true, false, null, "134000000000000001"));
    }

    private MockHttpServletResponse signInDirectory(OidcLogin login, Authentication auth) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        login.onAuthenticationSuccess(new MockHttpServletRequest(), response, auth);
        return response;
    }

    private void assertDirectoryRefused(OidcLogin login, Authentication auth) throws Exception {
        MockHttpServletResponse response = signInDirectory(login, auth);
        assertNoSession(response);
        assertThat(response.getRedirectedUrl()).isEqualTo(OidcLogin.FAILURE_URL);
        verify(securityService, never()).lookupUser(any());
    }

    @Test
    @DisplayName("directory: a verified e-mail that points at one account signs in as that account, under its own name")
    void directoryMapsToTheAccount() throws Exception {
        givenAccounts("mail", "jean.dupont@corp.fr", new SecurityService.AccountMatch(List.of("jdupont"), true));
        givenJean();

        MockHttpServletResponse response = signInDirectory(directory("email", "mail", ""),
                idp(IDP, true, "email", "jean.dupont@corp.fr"));

        Claims claims = sessionOf(response);
        assertThat(claims.getSubject()).isEqualTo("jdupont");           // like a password login: no visitor suffix
        assertThat(claims.get(JwtUtils.PWD_LAST_SET_CLAIM, String.class)).isEqualTo("134000000000000001");
        assertThat(response.getRedirectedUrl()).isEqualTo("/");
        verify(audit).logSecurityEvent(any(), eq("jdupont"), eq("LOGIN"), any());
        verify(securityService).lookupUser("jdupont");                    // that account's SIDs, no other
    }

    @Test
    @DisplayName("directory: the claim's value is trimmed, and handed to the directory as it is (AD ignores case)")
    void directoryTrimsTheValue() throws Exception {
        givenAccounts("userPrincipalName", "Jean.Dupont@Corp.fr", new SecurityService.AccountMatch(List.of("jdupont"), true));
        givenJean();

        MockHttpServletResponse response = signInDirectory(directory("upn", "userPrincipalName", ""),
                idp(IDP, null, "upn", "  Jean.Dupont@Corp.fr "));

        assertThat(sessionOf(response).getSubject()).isEqualTo("jdupont");
    }

    @Test
    @DisplayName("directory: no account, several accounts, or an unreadable directory, refuse the login")
    void directoryRefusesNoneSeveralOrUnreachable() throws Exception {
        OidcLogin login = directory("email", "mail", "");

        givenAccounts("mail", "ghost@corp.fr", new SecurityService.AccountMatch(List.of(), true));
        assertDirectoryRefused(login, idp(IDP, true, "email", "ghost@corp.fr"));

        givenAccounts("mail", "shared@corp.fr", new SecurityService.AccountMatch(List.of("a", "b"), true));
        assertDirectoryRefused(login, idp(IDP, true, "email", "shared@corp.fr"));

        givenAccounts("mail", "any@corp.fr", new SecurityService.AccountMatch(List.of(), false));
        assertDirectoryRefused(login, idp(IDP, true, "email", "any@corp.fr"));
    }

    @Test
    @DisplayName("directory: a missing or blank claim is refused, and the directory is never asked")
    void directoryRefusesMissingClaim() throws Exception {
        OidcLogin login = directory("email", "mail", "");
        assertDirectoryRefused(login, idp(IDP, true, "email", null));
        assertDirectoryRefused(login, idp(IDP, true, "email", "   "));
        verify(securityService, never()).findAccounts(any(), any());
    }

    @Test
    @DisplayName("directory: an e-mail the provider did not verify is refused; a UPN needs no such flag")
    void directoryVerifiedEmail() throws Exception {
        givenAccounts("mail", "jean.dupont@corp.fr", new SecurityService.AccountMatch(List.of("jdupont"), true));
        givenAccounts("userPrincipalName", "jdupont@corp.fr", new SecurityService.AccountMatch(List.of("jdupont"), true));

        assertDirectoryRefused(directory("email", "mail", ""), idp(IDP, false, "email", "jean.dupont@corp.fr"));
        assertDirectoryRefused(directory("email", "mail", ""), idp(IDP, null, "email", "jean.dupont@corp.fr"));

        givenJean();
        assertThat(sessionOf(signInDirectory(directory("upn", "userPrincipalName", ""),
                idp(IDP, null, "upn", "jdupont@corp.fr"))).getSubject()).isEqualTo("jdupont");
        // …unless the administrator asks for it all the same.
        org.mockito.Mockito.clearInvocations(securityService);
        assertDirectoryRefused(directory("upn", "userPrincipalName", "true"), idp(IDP, null, "upn", "jdupont@corp.fr"));
    }

    @Test
    @DisplayName("directory: a token from another issuer or tenant is refused")
    void directoryRefusesOtherIssuer() throws Exception {
        givenAccounts("mail", "jean.dupont@corp.fr", new SecurityService.AccountMatch(List.of("jdupont"), true));
        OidcLogin login = directory("email", "mail", "");
        assertDirectoryRefused(login, idp("https://idp.example/realms/other", true, "email", "jean.dupont@corp.fr"));
        assertDirectoryRefused(login, idp("https://login.microsoftonline.com/another-tenant/v2.0", true, "email", "jean.dupont@corp.fr"));
        assertDirectoryRefused(login, idp(null, true, "email", "jean.dupont@corp.fr"));
    }

    @Test
    @DisplayName("directory: a disabled account opens no session")
    void directoryRefusesDisabledAccount() throws Exception {
        givenAccounts("mail", "jean.dupont@corp.fr", new SecurityService.AccountMatch(List.of("jdupont"), true));
        when(securityService.lookupUser("jdupont")).thenReturn(new DirectoryUser(List.of(), true, true, true, null, null));

        MockHttpServletResponse response = signInDirectory(directory("email", "mail", ""),
                idp(IDP, true, "email", "jean.dupont@corp.fr"));

        assertNoSession(response);
        assertThat(response.getRedirectedUrl()).isEqualTo(OidcLogin.FAILURE_URL);
    }

    @Test
    @DisplayName("directory: two people get two sessions, each with its own account")
    void directoryTellsPeopleApart() throws Exception {
        givenAccounts("mail", "jean.dupont@corp.fr", new SecurityService.AccountMatch(List.of("jdupont"), true));
        givenAccounts("mail", "marie.martin@corp.fr", new SecurityService.AccountMatch(List.of("mmartin"), true));
        givenJean();
        when(securityService.lookupUser("mmartin")).thenReturn(
                new DirectoryUser(List.of("S-1-5-21-1-2-3-2002"), true, true, false, null, "1"));
        OidcLogin login = directory("email", "mail", "");

        String jean = sessionOf(signInDirectory(login, idp(IDP, true, "email", "jean.dupont@corp.fr"))).getSubject();
        String marie = sessionOf(signInDirectory(login, idp(IDP, true, "email", "marie.martin@corp.fr"))).getSubject();

        assertThat(jean).isEqualTo("jdupont");
        assertThat(marie).isEqualTo("mmartin");
    }

    @Test
    @DisplayName("directory: the demo-only behaviours are off")
    void directoryHasNoDemoBehaviour() {
        OidcLogin login = directory("email", "mail", "");
        assertThat(login.isFixedUser("demo")).isFalse();
        assertThat(login.buttonLabel()).isEqualTo("Se connecter avec l'authentification unique");
        assertThat(login.loginUrl()).isEqualTo("/oauth2/authorization/sso");
    }

    @Test
    @DisplayName("directory: a wrong configuration stops the application at start, saying what to set")
    void directoryRefusesBadConfiguration() {
        // valid
        directory("email", "mail", "").validate();
        directory("upn", "userPrincipalName", "false").validate();

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () ->
                new OidcLogin(true, "directory", "sso", "demo", "", "email", "mail", "", "", false,
                        SECRET, securityService, jwtUtils, new SessionCookies(true), audit).validate());  // no issuer
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> directory("", "mail", "").validate());                                  // no claim
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> directory("email", "mail)(objectClass=*", "").validate());              // not an attribute name
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () ->
                new OidcLogin(true, "nonsense", "sso", "demo", IDP, "email", "mail", "", "", false,
                        SECRET, securityService, jwtUtils, new SessionCookies(true), audit).validate());
    }
}
