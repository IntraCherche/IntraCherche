package eu.intracherche.intracherche.security;

import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.security.cert.CertPathBuilderException;
import javax.naming.CommunicationException;
import javax.net.ssl.SSLHandshakeException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the administrator reads when nobody can sign in because of the directory, not the password. */
class LdapFailureDiagnosisTest {

    /** The exact failure seen on a server that had not imported the directory's CA. */
    @Test
    void a_rejected_certificate_reported_as_a_closed_connection_names_the_certificate() {
        Throwable failure = new RuntimeException("InternalAuthenticationServiceException",
                new SocketException("Connection or outbound has closed"));

        String hint = LdapFailureDiagnosis.of(failure).orElseThrow();

        assertTrue(hint.contains("certificate"), hint);
        assertTrue(hint.contains("nobody can sign in"), hint);
        assertTrue(hint.contains("Connexion LDAPS au contrôleur de domaine"), hint);
    }

    @Test
    void an_untrusted_certificate_chain_says_the_ca_must_be_imported() {
        Throwable failure = new CommunicationException("simple bind failed")
                .initCause(new SSLHandshakeException("PKIX path building failed")
                        .initCause(new CertPathBuilderException("unable to find valid certification path to requested target")));

        String hint = LdapFailureDiagnosis.of(failure).orElseThrow();

        assertTrue(hint.contains("not trusted"), hint);
        assertTrue(hint.contains("import"), hint);
    }

    @Test
    void an_unresolvable_directory_name_is_quoted() {
        Throwable failure = new RuntimeException("login", new UnknownHostException("dc.corp.local"));

        String hint = LdapFailureDiagnosis.of(failure).orElseThrow();

        assertTrue(hint.contains("'dc.corp.local' does not resolve"), hint);
    }

    @Test
    void an_unreachable_directory_mentions_the_port_and_the_firewall() {
        Throwable failure = new RuntimeException("login", new ConnectException("Connection refused"));

        assertTrue(LdapFailureDiagnosis.of(failure).orElseThrow().contains("636"));
    }

    /** A wrong password is the user's: the log must not blame the directory. */
    @Test
    void a_wrong_password_gets_no_infrastructure_hint() {
        Throwable failure = new RuntimeException("Bad credentials",
                new javax.naming.AuthenticationException("[LDAP: error code 49 - 80090308: LdapErr: DSID-0C09044E, data 52e]"));

        assertEquals(java.util.Optional.empty(), LdapFailureDiagnosis.of(failure));
    }

    @Test
    void a_cause_that_points_to_itself_does_not_loop() {
        RuntimeException self = new RuntimeException("loop") {
            @Override public synchronized Throwable getCause() { return this; }
        };

        assertEquals(java.util.Optional.empty(), LdapFailureDiagnosis.of(self));
    }
}
