package eu.intracherche.intracherche.sherlock;

import eu.intracherche.intracherche.audit.AuditService;
import eu.intracherche.intracherche.config.LlmConfig;
import eu.intracherche.intracherche.security.SecurityService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/sherlock")
public class SherlockController {

    private final SherlockService sherlockService;
    private final SecurityService securityService;
    private final AuditService auditService;
    private final LlmConfig llmConfig;
    private final LlmService llmService;
    private final SherlockQuota quota;
    private final SherlockQuestionDao dao;

    public SherlockController(SherlockService sherlockService, SecurityService securityService,
                              AuditService auditService, LlmConfig llmConfig,
                              LlmService llmService,
                              SherlockQuota quota, SherlockQuestionDao dao) {
        this.sherlockService = sherlockService;
        this.securityService = securityService;
        this.auditService = auditService;
        this.llmConfig = llmConfig;
        this.llmService = llmService;
        this.quota = quota;
        this.dao = dao;
    }

    @PostMapping("/questions")
    public ResponseEntity<?> ask(HttpServletRequest httpRequest, @RequestBody AskRequest req)
            throws SQLException, InterruptedException {
        if (req.question() == null || req.question().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        ResponseEntity<?> refused = refusal();
        if (refused != null) return refused;
        String username = currentUsername();

        int maxPending = llmConfig.getQuota().getMaxPending();
        if (maxPending > 0 && dao.countPending(username) >= maxPending) {
            return ResponseEntity.status(429).body(Map.of("error",
                    "Vous avez déjà " + maxPending + " question(s) en attente de réponse. Attendez qu'une réponse "
                            + "arrive avant d'en poser une autre."));
        }
        SherlockQuota.Consumption consumption = quota.consume(username, groupSids());
        if (!consumption.accepted()) {
            SherlockQuota.Usage usage = consumption.usage();
            auditService.logSecurityEvent(httpRequest, username, "SHERLOCK_QUOTA",
                    "Quota quotidien atteint (" + usage.used() + "/" + usage.limit() + ", " + usage.tier() + ")");
            return ResponseEntity.status(429).body(Map.of(
                    "error", "Vous avez posé vos " + usage.limit() + " questions du jour à Sherlock ; "
                            + "le compteur repart à zéro à minuit.",
                    "quota", quotaBody(usage)));
        }

        long askedAt = System.currentTimeMillis();
        boolean queued = false;
        try {
            auditService.logSherlockQuestion(httpRequest, username, req.question().trim());
            String userEmail = securityService.getEmailForUser(username).orElse("");
            List<String> userSids = securityService.getEffectiveSids(username);
            SherlockQuestion q = sherlockService.ask(username, userEmail, userSids, req.question().trim(),
                    req.parentId(), req.indices(), req.documentId(), req.documentName());
            queued = true;
            return ResponseEntity.accepted().body(toResponse(q));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "La question d'origine est introuvable."));
        } finally {
            // Refused after counting (queue full, unknown parent…): nothing was asked.
            if (!queued) quota.refund(username, askedAt);
        }
    }

    /** The user's questions today against their quota; {@code limit} null when unlimited. */
    @GetMapping("/quota")
    public Map<String, Object> quota() throws SQLException {
        return quotaBody(quota.usage(currentUsername(), groupSids()));
    }

    private static Map<String, Object> quotaBody(SherlockQuota.Usage usage) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("tier", usage.tier().name());
        body.put("used", usage.used());
        body.put("limit", usage.limit() > 0 ? usage.limit() : null);
        return body;
    }

    /** The user's group SIDs, as the session carries them: what decides the tier. */
    private List<String> groupSids() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getAuthorities() == null) return List.of();
        return auth.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList();
    }

    /** Why Sherlock's model may not be used for this request now, or null when it may. */
    private ResponseEntity<?> refusal() {
        if (!llmConfig.isEnabled()) {
            return ResponseEntity.status(403)
                    .body(Map.of("error", "Sherlock est désactivé sur ce serveur. Contactez votre administrateur."));
        }
        if (!isSherlockAuthorized()) {
            auditService.logSecurityEvent("ACCESS_DENIED", "Sherlock (hors du groupe autorisé)");
            return ResponseEntity.status(403)
                    .body(Map.of("error", "Vous n'êtes pas autorisé(e) à utiliser Sherlock. Contactez votre administrateur pour rejoindre le groupe IntraCherche-Sherlock."));
        }
        // Reject up front when Ollama is unreachable: a queued question would only
        // sit until synthesis fails, leaving the user waiting for a FAILED status.
        if (llmConfig.isEnabled() && !llmService.isHealthy()) {
            return ResponseEntity.status(503)
                    .body(Map.of("error", "Sherlock est momentanément indisponible : le service de langage ne répond pas. "
                            + "Réessayez dans quelques minutes ou contactez votre administrateur si le problème persiste."));
        }
        return null;
    }

    @ExceptionHandler(SherlockQueueFullException.class)
    public ResponseEntity<Map<String, String>> handleQueueFull() {
        return ResponseEntity.status(503)
                .body(Map.of("error", "La file d'attente de Sherlock est saturée. Veuillez réessayer dans quelques minutes."));
    }

    @GetMapping("/questions")
    public List<SherlockQuestionResponse> list(
            @RequestParam(name = "limit", defaultValue = "20") int limit,
            @RequestParam(name = "offset", defaultValue = "0") int offset) throws SQLException {
        return sherlockService.getQuestionsForUser(currentUsername(), limit, offset)
                .stream().map(this::toResponse).toList();
    }

    @DeleteMapping("/questions/{id}")
    public ResponseEntity<Void> delete(@PathVariable("id") String id) throws SQLException {
        boolean deleted = sherlockService.deleteQuestion(id, currentUsername());
        return deleted ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    @GetMapping("/questions/{id}")
    public ResponseEntity<SherlockQuestionResponse> getById(@PathVariable("id") String id) throws SQLException {
        SherlockQuestion q = sherlockService.getQuestionById(id, currentUsername());
        if (q == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(toResponse(q));
    }

    private boolean isSherlockAuthorized() {
        return llmConfig.canUseSherlock(SecurityContextHolder.getContext().getAuthentication());
    }

    private String currentUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth.getName();
    }

    private SherlockQuestionResponse toResponse(SherlockQuestion q) {
        return new SherlockQuestionResponse(
                q.id(), q.question(), q.status().name(),
                q.answer(), q.citations(), q.createdAt(), q.answeredAt(), q.parentId(), q.indices(), q.quotes(),
                q.documentId(), q.documentName(), q.spelling(), q.notice()
        );
    }

    /**
     * {@code indices}: omitted/null follows the parent question's scope, empty means every index.
     * {@code documentId}: the file to ask about; omitted/null follows the parent question.
     */
    record AskRequest(String question, String parentId, List<String> indices,
                      String documentId, String documentName) {}
}
