package eu.intracherche.intracherche.audit;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;

@Service
public class AuditService {

    private static final Logger audit = LoggerFactory.getLogger("AUDIT");

    /** IP of the trusted reverse proxy, if any (e.g. "192.168.1.1"). Empty = no proxy. */
    @Value("${audit.trusted-proxy:}")
    private String trustedProxy;

    public void logSearch(HttpServletRequest request, String username, String query, List<String> indices) {
        audit.info("SEARCH | user={} | ip={} | query={} | indices={}",
                escape(username),
                escape(resolveClientIp(request)),
                quote(query),
                escape(String.join(",", indices)));
    }

    public void logSherlockQuestion(HttpServletRequest request, String username, String question) {
        audit.info("SHERLOCK | user={} | ip={} | question={}",
                escape(username),
                escape(resolveClientIp(request)),
                quote(question));
    }

    /**
     * Records an administrative action that changes how the product behaves.
     *
     * Searches and Sherlock questions are traced because they touch documents; an
     * action like a full re-index costs days of machine time and is triggered by one
     * person, so the same line has to say who, from where, and what it cost.
     */
    public void logAdminAction(HttpServletRequest request, String username, String action, String details) {
        audit.info("ADMIN | user={} | ip={} | action={} | details={}",
                escape(username),
                escape(resolveClientIp(request)),
                escape(action),
                quote(details));
    }

    /**
     * Records an authentication or authorization event: a login, a logout, a session ended by the
     * server, or a request refused. Refusals are traced like the actions they block, because a
     * run of them is what an attempt to reach what one may not looks like.
     */
    public void logSecurityEvent(HttpServletRequest request, String username, String event, String details) {
        audit.info("SECURITY | user={} | ip={} | event={} | details={}",
                escape(username),
                escape(request != null ? resolveClientIp(request) : null),
                escape(event),
                quote(details));
    }

    /** Same, for the user and request being served (from the security and request contexts). */
    public void logSecurityEvent(String event, String details) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        HttpServletRequest request = RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs
                ? attrs.getRequest() : null;
        logSecurityEvent(request, auth != null ? auth.getName() : null, event, details);
    }

    private static String quote(String value) {
        return "\"" + escape(value) + "\"";
    }

    // Every audit entry must stay on one line: a raw line break in a query would let a user
    // write a forged entry under someone else's name. Escaping backslash and quote too keeps
    // quoted values unambiguous (a query ending in "\" cannot swallow the closing quote).
    static String escape(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    // U+2028/U+2029 are not ISO controls but editors and log viewers break lines on them
                    if (Character.isISOControl(c) || c == 0x2028 || c == 0x2029) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    // Trust X-Forwarded-For only when the actual TCP connection comes from the configured
    // trusted proxy (audit.trusted-proxy). Without this check, any client can spoof the IP.
    private String resolveClientIp(HttpServletRequest request) {
        String remoteAddr = request.getRemoteAddr();
        if (trustedProxy != null && !trustedProxy.isBlank() && trustedProxy.equals(remoteAddr)) {
            String xff = request.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                return xff.split(",")[0].trim();
            }
        }
        return remoteAddr;
    }
}
