package eu.intracherche.intracherche.config;

import org.apache.catalina.connector.Connector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.List;

/**
 * HTTPS served by the application itself, as a fresh install sets it up (install.sh generates a
 * self-signed certificate; see generate-certificate.sh):
 * <ul>
 *   <li>a second, plain-HTTP connector on {@code app.https.redirect-from-port} (8080), whose only
 *       job is to send browsers to HTTPS — the redirect itself is in SecurityConfig — so that an
 *       old bookmark or a typed {@code http://} address still works;</li>
 *   <li>at start-up, the state of the certificate in the log, in the words an admin needs: which
 *       names it covers (a browser warns about any other), whether browsers will trust it, when it
 *       expires — or, over plain HTTP, that passwords travel in clear.</li>
 * </ul>
 */
@Configuration
public class HttpsConfig {

    private static final Logger log = LoggerFactory.getLogger(HttpsConfig.class);
    private static final Duration EXPIRY_WARNING = Duration.ofDays(30);

    @Value("${server.ssl.enabled:false}")
    private boolean sslEnabled;

    @Value("${server.port:8080}")
    private int httpsPort;

    @Value("${app.https.redirect-from-port:8080}")
    private int redirectFromPort;

    @Value("${server.ssl.key-store:}")
    private String keyStore;

    @Value("${server.ssl.key-store-password:}")
    private String keyStorePassword;

    @Value("${server.ssl.key-store-type:PKCS12}")
    private String keyStoreType;

    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> httpRedirectConnector() {
        return factory -> {
            if (!sslEnabled || redirectFromPort <= 0 || redirectFromPort == httpsPort) return;
            Connector connector = new Connector(TomcatServletWebServerFactory.DEFAULT_PROTOCOL);
            connector.setScheme("http");
            connector.setPort(redirectFromPort);
            connector.setSecure(false);
            connector.setRedirectPort(httpsPort);
            factory.addAdditionalConnectors(connector);
        };
    }

    @EventListener(ApplicationReadyEvent.class)
    public void describeTransport() {
        if (!sslEnabled) {
            log.warn("HTTPS désactivé : identifiants et documents circulent en clair sur le réseau. "
                    + "Voir INSTALL.md, « Accès sécurisé (HTTPS) ».");
            return;
        }
        try {
            X509Certificate cert = certificate();
            if (cert == null) {
                log.warn("HTTPS actif, mais le certificat de {} n'a pas pu être lu pour vérification.", keyStore);
                return;
            }
            log.info("HTTPS actif sur le port {}{} — certificat valable pour : {} (jusqu'au {})",
                    httpsPort,
                    redirectFromPort > 0 ? ", le port " + redirectFromPort + " redirige vers HTTPS" : "",
                    String.join(", ", names(cert)), cert.getNotAfter().toInstant());
            if (cert.getSubjectX500Principal().equals(cert.getIssuerX500Principal())) {
                log.warn("Certificat HTTPS auto-signé : les navigateurs afficheront un avertissement tant qu'il "
                        + "n'est pas déployé sur les postes (GPO, fichier /etc/intracherche/intracherche.crt). "
                        + "Un nom absent de la liste ci-dessus provoque aussi un avertissement. "
                        + "Voir INSTALL.md, « Accès sécurisé (HTTPS) ».");
            }
            Instant notAfter = cert.getNotAfter().toInstant();
            if (Instant.now().isAfter(notAfter)) {
                log.error("Certificat HTTPS EXPIRÉ depuis le {} : les navigateurs refusent la connexion. "
                        + "Renouvelez-le : sudo /opt/intracherche/generate-certificate.sh puis redémarrez.", notAfter);
            } else if (Instant.now().plus(EXPIRY_WARNING).isAfter(notAfter)) {
                log.warn("Le certificat HTTPS expire le {}. Renouvelez-le avant : "
                        + "sudo /opt/intracherche/generate-certificate.sh puis redémarrez.", notAfter);
            }
        } catch (Exception e) {
            log.warn("HTTPS actif, mais le certificat n'a pas pu être lu pour vérification : {}", e.toString());
        }
    }

    private X509Certificate certificate() throws Exception {
        if (keyStore.isBlank()) return null;
        Path path = Path.of(keyStore.replaceFirst("^file:", ""));
        if (!Files.isReadable(path)) return null;
        KeyStore ks = KeyStore.getInstance(keyStoreType);
        try (InputStream in = Files.newInputStream(path)) {
            ks.load(in, keyStorePassword.toCharArray());
        }
        for (Enumeration<String> aliases = ks.aliases(); aliases.hasMoreElements(); ) {
            if (ks.getCertificate(aliases.nextElement()) instanceof X509Certificate x509) return x509;
        }
        return null;
    }

    private static List<String> names(X509Certificate cert) throws Exception {
        List<String> names = new ArrayList<>();
        Collection<List<?>> sans = cert.getSubjectAlternativeNames();
        if (sans != null) {
            for (List<?> san : sans) names.add(String.valueOf(san.get(1)));
        }
        if (names.isEmpty()) names.add(cert.getSubjectX500Principal().getName());
        return names;
    }
}
