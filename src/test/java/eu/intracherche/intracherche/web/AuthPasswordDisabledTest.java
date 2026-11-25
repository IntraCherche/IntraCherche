package eu.intracherche.intracherche.web;

import eu.intracherche.intracherche.audit.AuditService;
import eu.intracherche.intracherche.security.OidcLogin;
import eu.intracherche.intracherche.web.dto.LoginRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * auth.password.enabled: false. The password login is off for everyone, with no exception and no emergency
 * door; the application refuses to start if that would leave nobody able to sign in.
 */
class AuthPasswordDisabledTest {

    private final AuthenticationManager directory = mock(AuthenticationManager.class);
    private final AuditService audit = mock(AuditService.class);
    private final OidcLogin oidc = mock(OidcLogin.class);

    private AuthController controller(boolean passwordEnabled, boolean oidcEnabled) {
        when(oidc.enabled()).thenReturn(oidcEnabled);
        when(oidc.loginUrl()).thenReturn("/oauth2/authorization/sso");
        when(oidc.buttonLabel()).thenReturn("Se connecter avec le SSO");
        AuthController controller = new AuthController();
        ReflectionTestUtils.setField(controller, "authenticationManager", directory);
        ReflectionTestUtils.setField(controller, "auditService", audit);
        ReflectionTestUtils.setField(controller, "oidcLogin", oidc);
        ReflectionTestUtils.setField(controller, "passwordEnabled", passwordEnabled);
        return controller;
    }

    private static HttpServletRequest request() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("192.0.2.10");
        return request;
    }

    @Test
    @DisplayName("the password login is refused for everyone, without touching the directory")
    void loginRefusedWithoutAnyCheck() {
        HttpServletResponse response = mock(HttpServletResponse.class);

        ResponseEntity<?> result = controller(false, true)
                .authenticateUser(new LoginRequest("alice", "right-password"), request(), response);

        assertThat(result.getStatusCode().value()).isEqualTo(403);
        assertThat(((Map<?, ?>) result.getBody()).get("message").toString()).contains("désactivée");
        verifyNoInteractions(directory);                                           // no bind, so no hint either
        verifyNoInteractions(response);                                            // no cookie of any kind
        verify(audit).logSecurityEvent(any(), eq("alice"), eq("LOGIN_REFUSED"), any());
    }

    @Test
    @DisplayName("the login page is told not to offer the form, whatever auth.passwordForm says")
    void optionsHideTheForm() {
        when(oidc.passwordFormHidden()).thenReturn(false);
        Map<String, Object> options = controller(false, true).options();

        assertThat(options.get("passwordEnabled")).isEqualTo(false);
        assertThat(options.get("passwordForm")).isEqualTo(false);
        assertThat(options.get("oidc")).isEqualTo(true);
    }

    @Test
    @DisplayName("by default the password login stays on and the form is offered")
    void defaultsUnchanged() {
        Map<String, Object> options = controller(true, false).options();

        assertThat(options.get("passwordEnabled")).isEqualTo(true);
        assertThat(options.get("passwordForm")).isEqualTo(true);
    }

    @Test
    @DisplayName("the application refuses to start when no way to sign in would remain")
    void refusesToStartWithNoWayIn() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> controller(false, false).checkSomeLoginIsPossible());
        assertThat(e.getMessage()).contains("auth.password.enabled").contains("auth.oidc.enabled");
    }

    @Test
    @DisplayName("it starts when single sign-on is on, or when the password login is")
    void startsWhenSomeLoginRemains() {
        assertDoesNotThrow(() -> controller(false, true).checkSomeLoginIsPossible());
        assertDoesNotThrow(() -> controller(true, false).checkSomeLoginIsPossible());
        assertDoesNotThrow(() -> controller(true, true).checkSomeLoginIsPossible());
    }
}
