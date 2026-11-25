package eu.intracherche.intracherche.web;

import eu.intracherche.intracherche.config.AdminConfig;
import eu.intracherche.intracherche.config.LlmConfig;
import eu.intracherche.intracherche.audit.AuditService;
import eu.intracherche.intracherche.security.DirectoryUser;
import eu.intracherche.intracherche.security.JwtUtils;
import eu.intracherche.intracherche.security.OidcLogin;
import eu.intracherche.intracherche.security.SecurityService;
import eu.intracherche.intracherche.security.SessionCookies;
import eu.intracherche.intracherche.web.dto.LoginRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * app.cookie.secure and jwt.expirationMinutes, as INSTALL.md describes them: with secure false
 * the cookie loses only its Secure flag — "Les cookies restent HttpOnly et SameSite=Strict" —
 * and its lifetime is the one set in jwt.expirationMinutes. Also what /login tells the frontend
 * about Sherlock when llm.enabled is false.
 */
class AuthCookieSettingsTest {

    private static final String SECRET =
            "test-secret-that-is-long-enough-for-hmac-sha-256-at-least-64-chars-long!!";

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void secure_true_marks_the_session_cookie_secure() {
        String cookie = loginCookie(controller(true, 1440, new LlmConfig()));

        assertThat(cookie).contains("Secure", "HttpOnly", "SameSite=Strict", "Max-Age=86400");
        // __Host-: only accepted over HTTPS, for this exact host, Path=/ — nothing can plant it.
        assertThat(cookie).startsWith("__Host-jwt=").contains("Path=/");
    }

    @Test
    void secure_false_drops_only_the_secure_flag() {
        String cookie = loginCookie(controller(false, 1440, new LlmConfig()));

        assertThat(cookie).doesNotContain("Secure").startsWith("jwt=");
        assertThat(cookie).contains("HttpOnly", "SameSite=Strict");
    }

    @Test
    void the_logout_cookie_follows_the_same_setting() {
        HttpServletResponse response = mock(HttpServletResponse.class);
        controller(false, 1440, new LlmConfig()).logout(request(), response);

        ArgumentCaptor<String> header = ArgumentCaptor.forClass(String.class);
        verify(response).setHeader(eq(HttpHeaders.SET_COOKIE), header.capture());
        assertThat(header.getValue()).doesNotContain("Secure").contains("Max-Age=0", "HttpOnly");
    }

    @Test
    void the_cookie_lives_as_long_as_jwt_expiration_minutes() {
        String cookie = loginCookie(controller(true, 480, new LlmConfig()));

        assertThat(cookie).contains("Max-Age=" + 480 * 60);
    }

    @Test
    void login_reports_sherlock_closed_when_llm_is_disabled() {
        LlmConfig llm = new LlmConfig();
        llm.setEnabled(false);
        AuthController controller = controller(true, 1440, llm);

        ResponseEntity<?> response = controller.authenticateUser(new LoginRequest("alice", "pw"),
                request(), mock(HttpServletResponse.class));

        assertThat(((Map<?, ?>) response.getBody()).get("canUseSherlock")).isEqualTo(false);
    }

    @Test
    void the_demo_password_login_gets_a_visitor_session_of_its_own() {
        JwtUtils jwt = new JwtUtils();
        ReflectionTestUtils.setField(jwt, "jwtSecret", SECRET);
        ReflectionTestUtils.setField(jwt, "jwtExpirationMinutes", 60);
        AuthController controller = controller(true, 60, new LlmConfig());
        ReflectionTestUtils.setField(controller, "jwtUtils", jwt);
        AuthenticationManager manager = mock(AuthenticationManager.class);
        when(manager.authenticate(any())).thenReturn(new UsernamePasswordAuthenticationToken(
                new User("demo", "", List.of()), null, List.of()));
        ReflectionTestUtils.setField(controller, "authenticationManager", manager);
        SecurityService directory = mock(SecurityService.class);
        when(directory.lookupUser("demo")).thenReturn(new DirectoryUser(
                List.of("S-1-1-0"), true, true, false, null, "1"));
        ReflectionTestUtils.setField(controller, "securityService", directory);
        OidcLogin oidc = mock(OidcLogin.class);
        when(oidc.isFixedUser("demo")).thenReturn(true);
        when(oidc.passwordVisitorSession("192.0.2.10")).thenReturn("demo|ab12cd34ef56ab12");
        ReflectionTestUtils.setField(controller, "oidcLogin", oidc);

        HttpServletResponse response = mock(HttpServletResponse.class);
        ResponseEntity<?> result = controller.authenticateUser(new LoginRequest("demo", "pw"), request(), response);

        Map<?, ?> body = (Map<?, ?>) result.getBody();
        assertThat(body.get("username")).isEqualTo("demo|ab12cd34ef56ab12");
        assertThat(body.get("displayName")).isEqualTo("Visiteur AB12");
        ArgumentCaptor<String> header = ArgumentCaptor.forClass(String.class);
        verify(response).setHeader(eq(HttpHeaders.SET_COOKIE), header.capture());
        String token = header.getValue().substring("__Host-jwt=".length(), header.getValue().indexOf(';'));
        assertThat(jwt.getUserNameFromJwtToken(token)).isEqualTo("demo|ab12cd34ef56ab12");
    }

    // ── Fixtures ─────────────────────────────────────────────────────────────

    private static AuthController controller(boolean secure, int expirationMinutes, LlmConfig llm) {
        JwtUtils jwt = new JwtUtils();
        ReflectionTestUtils.setField(jwt, "jwtSecret", SECRET);
        ReflectionTestUtils.setField(jwt, "jwtExpirationMinutes", expirationMinutes);

        AuthenticationManager authenticationManager = mock(AuthenticationManager.class);
        when(authenticationManager.authenticate(any())).thenReturn(new UsernamePasswordAuthenticationToken(
                new User("alice", "", List.of()), null, List.of()));

        AuthController controller = new AuthController();
        ReflectionTestUtils.setField(controller, "sessionCookies", new SessionCookies(secure));
        ReflectionTestUtils.setField(controller, "authenticationManager", authenticationManager);
        ReflectionTestUtils.setField(controller, "jwtUtils", jwt);
        ReflectionTestUtils.setField(controller, "adminConfig", new AdminConfig());
        ReflectionTestUtils.setField(controller, "llmConfig", llm);
        SecurityService directory = mock(SecurityService.class);
        when(directory.lookupUser("alice")).thenReturn(new DirectoryUser(
                List.of("S-1-1-0"), true, true, false, null, "134000000000000000"));
        ReflectionTestUtils.setField(controller, "securityService", directory);
        ReflectionTestUtils.setField(controller, "auditService", mock(AuditService.class));
        ReflectionTestUtils.setField(controller, "oidcLogin", mock(OidcLogin.class));
        return controller;
    }

    private static HttpServletRequest request() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("192.0.2.10");
        return request;
    }

    private static String loginCookie(AuthController controller) {
        HttpServletResponse response = mock(HttpServletResponse.class);
        ResponseEntity<?> result = controller.authenticateUser(new LoginRequest("alice", "pw"),
                request(), response);
        assertThat(result.getStatusCode().value()).isEqualTo(200);

        ArgumentCaptor<String> header = ArgumentCaptor.forClass(String.class);
        verify(response).setHeader(eq(HttpHeaders.SET_COOKIE), header.capture());
        return header.getValue();
    }
}
