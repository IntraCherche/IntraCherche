package eu.intracherche.intracherche.security;

import eu.intracherche.intracherche.audit.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.ldap.core.ContextSource;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The real filter chain of {@link SecurityConfig} with the OpenID Connect login switched on: the
 * way out to Google is reachable without a session, carries the protections of the exchange
 * (state, nonce, PKCE), and an unauthenticated API call is still a 401 rather than a redirect
 * to Google.
 */
@SpringJUnitWebConfig(classes = OidcLoginWiringTest.Config.class)
class OidcLoginWiringTest {

    @Configuration
    @EnableWebSecurity
    @EnableWebMvc
    static class Config {
        @Bean
        ClientRegistrationRepository clients() {
            ClientRegistration google = CommonOAuth2Provider.GOOGLE.getBuilder("google")
                    .clientId("test-client-id").clientSecret("test-secret").scope("openid", "email")
                    .redirectUri("https://demo.example/login/oauth2/code/google").build();
            return new InMemoryClientRegistrationRepository(google);
        }

        @Bean
        SecurityFilterChain chain(HttpSecurity http, ObjectProvider<ClientRegistrationRepository> clients)
                throws Exception {
            SessionCookies cookies = new SessionCookies(true);
            AuthTokenFilter tokenFilter = new AuthTokenFilter();
            ReflectionTestUtils.setField(tokenFilter, "jwtUtils", mock(JwtUtils.class));
            ReflectionTestUtils.setField(tokenFilter, "sessionGuard", mock(SessionGuard.class));
            ReflectionTestUtils.setField(tokenFilter, "sessionCookies", cookies);

            OidcLogin oidc = new OidcLogin(true, "demo-fixed", "google", "demo",
                    "https://accounts.google.com", "email", "mail", "", "", true, "s",
                    mock(SecurityService.class), mock(JwtUtils.class), cookies,
                    mock(AuditService.class));

            return new SecurityConfig(mock(SidAuthoritiesPopulator.class), mock(ContextSource.class))
                    .filterChain(http, tokenFilter, cookies, oidc, clients);
        }
    }

    @Autowired
    private WebApplicationContext context;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", jakarta.servlet.Filter.class)).build();
    }

    @Test
    @DisplayName("the way out redirects to Google with state, nonce and a PKCE challenge, without any session")
    void authorizationRequest() throws Exception {
        String location = mvc.perform(get("/oauth2/authorization/google"))
                .andExpect(status().is3xxRedirection())
                .andReturn().getResponse().getRedirectedUrl();

        assertThat(location).startsWith("https://accounts.google.com/o/oauth2/v2/auth")
                .contains("response_type=code", "client_id=test-client-id", "scope=openid%20email")
                .contains("state=", "nonce=", "code_challenge=", "code_challenge_method=S256")
                .contains("redirect_uri=https://demo.example/login/oauth2/code/google");
    }

    @Test
    @DisplayName("an API call without a session is still a 401, not a redirect to Google")
    void apiStays401() throws Exception {
        // The entry point reads the servlet path, which a container sets and MockMvc must be told.
        mvc.perform(get("/api/search").servletPath("/api/search")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a page request without a session goes to the home page, never straight to Google")
    void pageRequestNeverRedirectsToGoogle() throws Exception {
        String location = mvc.perform(get("/some-page").servletPath("/some-page"))
                .andExpect(status().is3xxRedirection())
                .andReturn().getResponse().getRedirectedUrl();
        assertThat(location).isEqualTo("/");
    }

    @Test
    @DisplayName("the login page is public and keeps its query string (a refusal shows its message there)")
    void loginPageIsNotRedirected() throws Exception {
        // Before, /login was not on the public list: an unauthenticated request was sent to "/", dropping
        // ?oidcError=1 and ?logoutError=1, so the message never showed and the page's cleanup never ran.
        mvc.perform(get("/login").servletPath("/login").param("oidcError", "1"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotIn(301, 302, 303, 307, 308, 401, 403));
    }

    @Test
    @DisplayName("a callback with no matching handshake is refused and sent back to the login page")
    void callbackWithoutHandshake() throws Exception {
        String location = mvc.perform(get("/login/oauth2/code/google").param("code", "x").param("state", "forged"))
                .andExpect(status().is3xxRedirection())
                .andReturn().getResponse().getRedirectedUrl();

        assertThat(location).isEqualTo(OidcLogin.FAILURE_URL);
    }
}
