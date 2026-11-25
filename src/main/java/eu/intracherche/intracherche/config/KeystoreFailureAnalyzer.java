package eu.intracherche.intracherche.config;

import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.boot.diagnostics.FailureAnalyzer;

import java.io.FileNotFoundException;
import java.nio.file.NoSuchFileException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The HTTPS keystore the configuration names cannot be opened.
 *
 * <p>The usual cause is an {@code application.yaml} carried over from another server (a test
 * VM moved to production, or restored after a reinstall): every installation creates its own
 * {@code intracherche.p12} with its own random password, so the password in the copied file
 * opens nothing here. Left alone, the start-up ends on "keystore password was incorrect" at the
 * bottom of a stack trace, which reads like a typing mistake.
 */
public class KeystoreFailureAnalyzer implements FailureAnalyzer {

    private static final Pattern STORE = Pattern.compile("Could not load store from '([^']*)'");

    @Override
    public FailureAnalysis analyze(Throwable failure) {
        String store = null;
        boolean wrongPassword = false;
        boolean missing = false;
        int depth = 0;
        for (Throwable t = failure; t != null && depth < 25; t = t.getCause() == t ? null : t.getCause(), depth++) {
            String message = t.getMessage() == null ? "" : t.getMessage();
            Matcher m = STORE.matcher(message);
            if (store == null && m.find()) store = m.group(1);
            if (message.contains("keystore password was incorrect")) wrongPassword = true;
            if (t instanceof NoSuchFileException || t instanceof FileNotFoundException) missing = true;
        }
        if (store == null || !(wrongPassword || missing)) return null;

        String description = wrongPassword
                ? "The HTTPS keystore " + store + " cannot be opened with the password written in application.yaml "
                  + "(server.ssl.key-store-password)."
                : "The HTTPS keystore " + store + " does not exist (server.ssl.key-store).";
        String action = "Every installation creates its own keystore with its own password, so an application.yaml copied "
                + "from another server does not match this one. Run: sudo /opt/intracherche/generate-certificate.sh "
                + "(it makes a new self-signed keystore protected by the password already in application.yaml), then restart "
                + "the service; browsers will warn once about the new certificate. If this server uses a certificate issued "
                + "by your own authority, restore its intracherche.p12 instead, as root:intracherche with mode 640. "
                + "See INSTALL.md, « Accès sécurisé (HTTPS) ».";
        return new FailureAnalysis(description, action, failure);
    }
}
