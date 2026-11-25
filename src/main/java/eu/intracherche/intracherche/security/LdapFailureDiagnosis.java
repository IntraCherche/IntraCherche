package eu.intracherche.intracherche.security;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.security.cert.CertPathBuilderException;
import java.security.cert.CertificateException;
import java.util.Optional;
import javax.net.ssl.SSLException;

/**
 * Turns an infrastructure failure behind a login into a sentence the administrator can act on.
 *
 * <p>A wrong password and a directory the server cannot talk to both reach the login as an
 * authentication failure, and the second is the one nobody can fix from the login page: the
 * user only sees "Invalid username or password". The cause is deep in the exception chain and
 * its text is cryptic (the JVM reports a rejected certificate as "Connection or outbound has
 * closed"), so this names the likely cause and where the guide explains the fix.
 *
 * <p>Nothing is returned for a wrong password or an unknown account: those are the user's, and
 * the log should not suggest otherwise.
 */
public final class LdapFailureDiagnosis {

    private static final String GUIDE = "INSTALL.md, « Connexion LDAPS au contrôleur de domaine »";

    private LdapFailureDiagnosis() {}

    public static Optional<String> of(Throwable failure) {
        boolean tlsRejected = false;
        boolean tlsFailed = false;
        String unknownHost = null;
        boolean unreachable = false;

        int depth = 0;
        for (Throwable t = failure; t != null && depth < 20; t = t.getCause() == t ? null : t.getCause(), depth++) {
            String message = t.getMessage() == null ? "" : t.getMessage();
            if (t instanceof CertPathBuilderException || t instanceof CertificateException
                    || message.contains("PKIX path building failed")
                    || message.contains("unable to find valid certification path")) {
                tlsRejected = true;
            }
            if (t instanceof SSLException || message.contains("Connection or outbound has closed")) {
                tlsFailed = true;
            }
            if (t instanceof UnknownHostException) {
                unknownHost = message;
            }
            if (t instanceof ConnectException || t instanceof SocketTimeoutException
                    || t instanceof NoRouteToHostException) {
                unreachable = true;
            }
        }

        if (tlsRejected) {
            return Optional.of("The LDAPS certificate of the directory is not trusted by this server's Java, so nobody can "
                    + "sign in until the CA that issued it is imported into the service's truststore. See " + GUIDE
                    + ", step 2 (import the CA), then restart the service.");
        }
        if (tlsFailed) {
            return Optional.of("The secure (TLS) connection to the directory could not be set up. The usual cause is a "
                    + "certificate this server's Java does not trust (CA not imported yet), so nobody can sign in until "
                    + "that is fixed. See " + GUIDE + ", step 2; check with: openssl s_client -connect <directory>:636.");
        }
        if (unknownHost != null) {
            return Optional.of("The directory server name '" + unknownHost + "' does not resolve from this server. "
                    + "Check spring.ldap.urls and the server's DNS or /etc/hosts. See " + GUIDE + ", step 1.");
        }
        if (unreachable) {
            return Optional.of("The directory server cannot be reached (wrong address or port, or a firewall blocking 636). "
                    + "Nobody can sign in until it can. See " + GUIDE + ".");
        }
        return Optional.empty();
    }
}
