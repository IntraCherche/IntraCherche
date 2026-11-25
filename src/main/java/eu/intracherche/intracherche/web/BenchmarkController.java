package eu.intracherche.intracherche.web;

import eu.intracherche.intracherche.audit.AuditService;
import eu.intracherche.intracherche.config.AdminConfig;
import eu.intracherche.intracherche.services.BenchmarkService;
import eu.intracherche.intracherche.services.BenchmarkService.BenchmarkType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/admin/benchmark")
public class BenchmarkController {

    private final BenchmarkService benchmarkService;
    private final AdminConfig adminConfig;
    private final AuditService auditService;

    public BenchmarkController(BenchmarkService benchmarkService, AdminConfig adminConfig, AuditService auditService) {
        this.benchmarkService = benchmarkService;
        this.adminConfig      = adminConfig;
        this.auditService     = auditService;
    }

    @GetMapping("/status")
    public ResponseEntity<?> status() {
        if (!isAdminAuthorized()) return forbidden();
        return ResponseEntity.ok(benchmarkService.getStatus());
    }

    @PostMapping("/start")
    public ResponseEntity<?> start(@RequestParam(name = "type", defaultValue = "text") String type) {
        if (!isAdminAuthorized()) return forbidden();
        BenchmarkType bt = "scanned".equalsIgnoreCase(type) ? BenchmarkType.SCANNED : BenchmarkType.TEXT;
        boolean accepted = benchmarkService.start(bt);
        if (!accepted) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    "L'indexeur doit être en pause et aucun benchmark en cours."));
        }
        return ResponseEntity.ok(Map.of("started", true, "type", bt.name()));
    }

    @PostMapping("/prepare-scanned")
    public ResponseEntity<?> prepareScanned() {
        if (!isAdminAuthorized()) return forbidden();
        boolean accepted = benchmarkService.prepareScanned();
        if (!accepted) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    "Une opération est déjà en cours."));
        }
        return ResponseEntity.ok(Map.of("preparing", true));
    }

    @PostMapping("/stop")
    public ResponseEntity<?> stop() {
        if (!isAdminAuthorized()) return forbidden();
        boolean stopped = benchmarkService.stop();
        return ResponseEntity.ok(Map.of("stopped", stopped));
    }

    @PostMapping("/reset")
    public ResponseEntity<?> reset() {
        if (!isAdminAuthorized()) return forbidden();
        benchmarkService.resetFailure();
        return ResponseEntity.ok(Map.of("reset", true));
    }

    // ── Auth helper (same pattern as AdminController) ─────────────────────────

    private boolean isAdminAuthorized() {
        return adminConfig.isAdmin(SecurityContextHolder.getContext().getAuthentication());
    }

    private ResponseEntity<Map<String, Object>> forbidden() {
        auditService.logSecurityEvent("ACCESS_DENIED", "administration (banc d'essai)");
        return ResponseEntity.status(403)
                .body(Map.of("error", "Accès non autorisé au panneau d'administration."));
    }
}
