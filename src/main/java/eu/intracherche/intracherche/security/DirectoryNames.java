package eu.intracherche.intracherche.security;

/**
 * Session names for the demo's Google login, where every visitor is the same directory account.
 *
 * <p>Everything that keys on the principal name — the Sherlock quota, each visitor's question
 * history, the audit log — must tell visitors apart, while the SIDs must stay those of the one
 * account. So a visitor's session is named {@code <account>|<visitor>}, and the directory lookup
 * reads only the part before the bar. {@code |} is not allowed in an AD account name, so such a
 * name can never be a real user's.
 */
public final class DirectoryNames {

    private static final char VISITOR_SEPARATOR = '|';

    private DirectoryNames() {}

    /** The directory account behind a session name: everything before the first {@code |}. */
    public static String of(String sessionName) {
        if (sessionName == null) return null;
        int bar = sessionName.indexOf(VISITOR_SEPARATOR);
        return bar < 0 ? sessionName : sessionName.substring(0, bar);
    }

    /**
     * The name to show for a session: a real account as it is, a visitor as "Visiteur 493C" (the first
     * four characters of the visitor id; the id itself stays the key for the quota and the history).
     */
    public static String display(String sessionName) {
        if (sessionName == null) return null;
        int bar = sessionName.indexOf(VISITOR_SEPARATOR);
        if (bar < 0) return sessionName;
        String id = sessionName.substring(bar + 1);
        return id.isEmpty() ? of(sessionName) : "Visiteur " + id.substring(0, Math.min(4, id.length())).toUpperCase();
    }

    /** The session name of one visitor acting as the directory account {@code account}. */
    public static String visitor(String account, String visitorId) {
        return account + VISITOR_SEPARATOR + visitorId;
    }
}
