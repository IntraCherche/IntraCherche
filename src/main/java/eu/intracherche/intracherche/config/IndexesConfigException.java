package eu.intracherche.intracherche.config;

/**
 * A mistake in indexes.yaml that the administrator must correct before the application can
 * start. {@link IndexesConfigFailureAnalyzer} prints it as a short message instead of the
 * stack trace Spring would otherwise bury it in.
 */
public class IndexesConfigException extends IllegalStateException {

    public IndexesConfigException(String message) {
        super(message);
    }

    public IndexesConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
