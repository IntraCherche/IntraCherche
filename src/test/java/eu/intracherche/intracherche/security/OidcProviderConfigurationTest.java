package eu.intracherche.intracherche.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientProperties;
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientPropertiesMapper;
import org.springframework.security.oauth2.client.registration.ClientRegistration;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * What INSTALL.md tells administrators about declaring the identity provider.
 *
 * <p>With {@code issuer-uri}, Spring Boot downloads the provider's discovery document while the application
 * starts. If the provider is down at that moment, the application does not start — including when an
 * administrator restarts it precisely to switch the password login back on during an outage. With the
 * endpoints written out, nothing is fetched at start-up.
 */
class OidcProviderConfigurationTest {

    private static Map<String, ClientRegistration> registrations(Map<String, String> properties) {
        OAuth2ClientProperties bound = new Binder(new MapConfigurationPropertySource(properties))
                .bind("spring.security.oauth2.client", OAuth2ClientProperties.class).get();
        return new OAuth2ClientPropertiesMapper(bound).asClientRegistrations();
    }

    private static final Map<String, String> REGISTRATION = Map.of(
            "spring.security.oauth2.client.registration.sso.client-id", "id",
            "spring.security.oauth2.client.registration.sso.client-secret", "secret",
            "spring.security.oauth2.client.registration.sso.scope", "openid,profile,email",
            "spring.security.oauth2.client.registration.sso.authorization-grant-type", "authorization_code",
            "spring.security.oauth2.client.registration.sso.redirect-uri", "https://ic.example/login/oauth2/code/sso",
            "spring.security.oauth2.client.registration.sso.provider", "sso");

    @Test
    @DisplayName("issuer-uri needs the provider to answer while the application starts")
    void issuerUriNeedsTheProviderAtStartup() {
        Map<String, String> props = new java.util.HashMap<>(REGISTRATION);
        props.put("spring.security.oauth2.client.provider.sso.issuer-uri", "http://127.0.0.1:9/realms/corp");   // nothing listens

        assertThrows(RuntimeException.class, () -> registrations(props));
    }

    @Test
    @DisplayName("with the endpoints written out the registration is built with no network access")
    void explicitEndpointsNeedNoNetwork() {
        Map<String, String> props = new java.util.HashMap<>(REGISTRATION);
        props.put("spring.security.oauth2.client.provider.sso.authorization-uri", "http://127.0.0.1:9/auth");
        props.put("spring.security.oauth2.client.provider.sso.token-uri", "http://127.0.0.1:9/token");
        props.put("spring.security.oauth2.client.provider.sso.jwk-set-uri", "http://127.0.0.1:9/certs");
        props.put("spring.security.oauth2.client.provider.sso.user-name-attribute", "sub");

        ClientRegistration sso = registrations(props).get("sso");

        assertThat(sso.getRegistrationId()).isEqualTo("sso");
        assertThat(sso.getScopes()).contains("openid");
        assertThat(sso.getProviderDetails().getJwkSetUri()).isEqualTo("http://127.0.0.1:9/certs");
    }
}
