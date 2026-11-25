package eu.intracherche.intracherche.web;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.TimeUnit;

/**
 * Receives the browser's reports of what the Content-Security-Policy blocked (see
 * SecurityConfig.CONTENT_SECURITY_POLICY) and logs them, so that "the document does not open" or
 * "the page is blank" has its cause in app.log instead of only in one user's browser console.
 *
 * <p>Unauthenticated by necessity (browsers send reports without credentials), so it only ever
 * logs: one line per distinct blocked resource per hour, each field cut short.
 */
@RestController
public class CspReportController {

    private static final Logger log = LoggerFactory.getLogger(CspReportController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_BODY = 8_192;
    private static final int MAX_FIELD = 200;

    private final Cache<String, Boolean> reported = Caffeine.newBuilder()
            .expireAfterWrite(1, TimeUnit.HOURS)
            .maximumSize(1_000)
            .build();

    @PostMapping(path = "/api/csp-report")
    public ResponseEntity<Void> report(@RequestBody(required = false) String body) {
        if (body == null || body.length() > MAX_BODY) return ResponseEntity.noContent().build();
        try {
            JsonNode root = MAPPER.readTree(body);
            // report-uri sends {"csp-report": {...}}; the Reporting API sends [{"body": {...}}].
            JsonNode r = root.has("csp-report") ? root.get("csp-report")
                    : root.isArray() && !root.isEmpty() ? root.get(0).path("body") : root;
            String directive = field(r, "violated-directive", "effectiveDirective");
            String blocked = field(r, "blocked-uri", "blockedURL");
            String page = field(r, "document-uri", "documentURL");
            if (reported.asMap().putIfAbsent(directive + "|" + blocked, Boolean.TRUE) == null) {
                log.warn("Politique de sécurité du contenu (CSP) : le navigateur a bloqué « {} » (règle {}) sur la page {}. "
                        + "Si une fonction de l'interface ne marche plus, c'est la cause probable — "
                        + "voir INSTALL.md, « En-têtes de sécurité du navigateur ».", blocked, directive, page);
            }
        } catch (Exception e) {
            log.debug("Unreadable CSP report ignored: {}", e.getMessage());
        }
        return ResponseEntity.noContent().build();
    }

    private static String field(JsonNode node, String name, String alternative) {
        String value = node.path(name).asText(node.path(alternative).asText(""));
        value = value.replaceAll("\\p{Cntrl}", " ");
        return value.length() > MAX_FIELD ? value.substring(0, MAX_FIELD) + "…" : value;
    }
}
