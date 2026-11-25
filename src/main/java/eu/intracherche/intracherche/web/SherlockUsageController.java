package eu.intracherche.intracherche.web;

import eu.intracherche.intracherche.audit.AuditService;
import eu.intracherche.intracherche.config.AdminConfig;
import eu.intracherche.intracherche.config.LlmConfig;
import eu.intracherche.intracherche.sherlock.SherlockQuota;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Who asked Sherlock how much today, for the admin panel: who hammers it, and who would be better
 * off in the premium tier.
 */
@RestController
@RequestMapping("/api/admin")
public class SherlockUsageController {

    /** More than a panel card shows; the busiest come first. */
    private static final int MAX_USERS = 100;

    private final SherlockQuota quota;
    private final LlmConfig llmConfig;
    private final AdminConfig adminConfig;
    private final AuditService auditService;

    public SherlockUsageController(SherlockQuota quota, LlmConfig llmConfig, AdminConfig adminConfig,
                                   AuditService auditService) {
        this.quota = quota;
        this.llmConfig = llmConfig;
        this.adminConfig = adminConfig;
        this.auditService = auditService;
    }

    @GetMapping("/sherlock-usage")
    public ResponseEntity<?> usage() throws SQLException {
        if (!adminConfig.isAdmin(SecurityContextHolder.getContext().getAuthentication())) {
            auditService.logSecurityEvent("ACCESS_DENIED", "administration");
            return ResponseEntity.status(403)
                    .body(Map.of("error", "Vous n'êtes pas autorisé(e) à accéder au panneau d'administration."));
        }
        Map<String, Object> limits = new LinkedHashMap<>();
        for (LlmConfig.Tier tier : LlmConfig.Tier.values()) {
            int limit = llmConfig.getQuota().limitOf(tier);
            limits.put(tier.name(), limit > 0 ? limit : null);
        }
        List<Map<String, Object>> users = quota.today(MAX_USERS).stream().map(u -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("username", u.username());
            row.put("tier", u.tier().name());
            row.put("used", u.used());
            row.put("limit", u.limit() > 0 ? u.limit() : null);
            return row;
        }).toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("enabled", llmConfig.isEnabled());
        body.put("limits", limits);
        body.put("users", users);
        return ResponseEntity.ok(body);
    }
}
