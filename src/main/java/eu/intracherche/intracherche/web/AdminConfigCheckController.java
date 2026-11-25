package eu.intracherche.intracherche.web;

import eu.intracherche.intracherche.audit.AuditService;
import eu.intracherche.intracherche.config.AdminConfig;
import eu.intracherche.intracherche.config.ConfigCheckService;
import eu.intracherche.intracherche.config.IndexesEditorService;
import eu.intracherche.intracherche.config.IndexesEditorService.DirDto;
import eu.intracherche.intracherche.config.IndexesEditorService.IndexDto;
import eu.intracherche.intracherche.config.MemoryEditorService;
import eu.intracherche.intracherche.config.SettingsEditorService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Configuration diagnosis and editing for the admin panel's Configuration tab. */
@RestController
@RequestMapping("/api/admin")
public class AdminConfigCheckController {

    private static final Logger log = LoggerFactory.getLogger(AdminConfigCheckController.class);

    /** Exit code systemd's Restart=on-failure acts on: any non-zero one. */
    static final int RESTART_EXIT_CODE = 3;

    private final AdminConfig adminConfig;
    private final ConfigCheckService configCheckService;
    private final IndexesEditorService indexesEditor;
    private final SettingsEditorService settingsEditor;
    private final MemoryEditorService memoryEditor;
    private final AuditService auditService;
    private final ConfigurableApplicationContext context;

    public AdminConfigCheckController(AdminConfig adminConfig, ConfigCheckService configCheckService,
                                      IndexesEditorService indexesEditor, SettingsEditorService settingsEditor, MemoryEditorService memoryEditor,
                                      AuditService auditService,
                                      ConfigurableApplicationContext context) {
        this.adminConfig = adminConfig;
        this.configCheckService = configCheckService;
        this.indexesEditor = indexesEditor;
        this.settingsEditor = settingsEditor;
        this.memoryEditor = memoryEditor;
        this.auditService = auditService;
        this.context = context;
    }

    @GetMapping("/config-check")
    public ResponseEntity<?> configCheck() {
        if (!isAdmin()) return forbidden();
        return ResponseEntity.ok(configCheckService.check());
    }

    @GetMapping("/indexes-config")
    public ResponseEntity<?> indexesConfig() {
        if (!isAdmin()) return forbidden();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("config", indexesEditor.read());
        body.put("restartSupported", restartSupported());
        return ResponseEntity.ok(body);
    }

    @PostMapping("/indexes-config/validate")
    public ResponseEntity<?> validate(@RequestBody List<IndexDto> indexes) {
        if (!isAdmin()) return forbidden();
        return ResponseEntity.ok(indexesEditor.validate(indexes));
    }

    @PutMapping("/indexes-config")
    public ResponseEntity<?> save(@RequestBody List<IndexDto> indexes, HttpServletRequest request) throws IOException {
        if (!isAdmin()) return forbidden();
        IndexesEditorService.SaveResult result = indexesEditor.save(indexes);
        if (result.saved()) {
            auditService.logAdminAction(request, username(), "INDEXES_CONFIG_SAVED",
                    indexes.size() + " index, sauvegarde " + result.backup());
        }
        return ResponseEntity.status(result.saved() ? 200 : 422).body(result);
    }

    @GetMapping("/settings")
    public ResponseEntity<?> settings() {
        if (!isAdmin()) return forbidden();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("config", settingsEditor.read());
        body.put("restartSupported", restartSupported());
        return ResponseEntity.ok(body);
    }

    @PostMapping("/settings/validate")
    public ResponseEntity<?> validateSettings(@RequestBody Map<String, Object> changes) {
        if (!isAdmin()) return forbidden();
        return ResponseEntity.ok(settingsEditor.validate(changes, authorities()));
    }

    @PutMapping("/settings")
    public ResponseEntity<?> saveSettings(@RequestBody Map<String, Object> changes, HttpServletRequest request)
            throws IOException {
        if (!isAdmin()) return forbidden();
        SettingsEditorService.SaveResult result = settingsEditor.save(changes, authorities());
        if (result.saved()) {
            auditService.logAdminAction(request, username(), "SETTINGS_SAVED",
                    String.join(", ", changes.keySet()) + " ; sauvegarde " + result.backup());
        }
        return ResponseEntity.status(result.saved() ? 200 : 422).body(result);
    }

    @PostMapping("/settings/test-ldap")
    public ResponseEntity<?> testLdap(@RequestBody Map<String, Object> candidate) {
        if (!isAdmin()) return forbidden();
        return settingsEditor.testLdap(candidate)
                .<ResponseEntity<?>>map(why -> ResponseEntity.ok(Map.of("ok", false, "message", why)))
                .orElseGet(() -> ResponseEntity.ok(Map.of("ok", true, "message", "Connexion à l'annuaire réussie.")));
    }

    @PostMapping("/settings/test-mail")
    public ResponseEntity<?> testMail(@RequestBody Map<String, Object> body) {
        if (!isAdmin()) return forbidden();
        @SuppressWarnings("unchecked")
        Map<String, Object> candidate = body.get("settings") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        String to = body.get("to") instanceof String t ? t : "";
        return settingsEditor.testMail(candidate, to)
                .<ResponseEntity<?>>map(why -> ResponseEntity.ok(Map.of("ok", false, "message", why)))
                .orElseGet(() -> ResponseEntity.ok(Map.of("ok", true,
                        "message", "Message d'essai envoyé à " + to.trim() + " : vérifiez sa boîte de réception.")));
    }

    public record ResolveGroupRequest(String name) {}

    @PostMapping("/settings/resolve-group")
    public ResponseEntity<?> resolveGroup(@RequestBody ResolveGroupRequest req) {
        if (!isAdmin()) return forbidden();
        try {
            return settingsEditor.resolveGroup(req.name())
                    .<ResponseEntity<?>>map(g -> ResponseEntity.ok(Map.of("name", g.name(), "sid", g.sid())))
                    .orElseGet(() -> ResponseEntity.status(404).body(Map.of("error",
                            "Aucun groupe de ce nom dans l'annuaire (nom antérieur à Windows 2000 attendu).")));
        } catch (RuntimeException e) {
            return ResponseEntity.status(503).body(Map.of("error", "L'annuaire n'a pas pu être interrogé."));
        }
    }

    @GetMapping("/memory")
    public ResponseEntity<?> memory() {
        if (!isAdmin()) return forbidden();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("config", memoryEditor.read());
        body.put("preview", memoryEditor.preview(new MemoryEditorService.Input(null, null, null)));
        body.put("restartSupported", restartSupported());
        return ResponseEntity.ok(body);
    }

    @PostMapping("/memory/preview")
    public ResponseEntity<?> memoryPreview(@RequestBody MemoryEditorService.Input input) {
        if (!isAdmin()) return forbidden();
        return ResponseEntity.ok(memoryEditor.preview(input));
    }

    @PutMapping("/memory")
    public ResponseEntity<?> saveMemory(@RequestBody MemoryEditorService.Input input, HttpServletRequest request)
            throws IOException {
        if (!isAdmin()) return forbidden();
        MemoryEditorService.SaveResult result = memoryEditor.save(input, authorities());
        if (result.saved()) {
            auditService.logAdminAction(request, username(), "MEMORY_SAVED", input.toString());
        }
        return ResponseEntity.status(result.saved() ? 200 : 422).body(result);
    }

    public record ShareTestRequest(String coreName, DirDto dir) {}

    @PostMapping("/indexes-config/test-share")
    public ResponseEntity<?> testShare(@RequestBody ShareTestRequest req) {
        if (!isAdmin()) return forbidden();
        return ResponseEntity.ok(indexesEditor.testShare(req.coreName(), req.dir()));
    }

    /**
     * Restarts the application by exiting with a non-zero code, which systemd's
     * {@code Restart=on-failure} turns into a start. Refused when not run by systemd, where the
     * exit would simply stop the application.
     */
    @PostMapping("/restart")
    public ResponseEntity<?> restart(HttpServletRequest request) {
        if (!isAdmin()) return forbidden();
        if (!restartSupported()) {
            return ResponseEntity.status(409).body(Map.of("error",
                    "Cette installation n'est pas lancée par systemd : redémarrez l'application manuellement."));
        }
        auditService.logAdminAction(request, username(), "APP_RESTART", "demandé depuis le panneau d'administration");
        Thread t = new Thread(() -> {
            try { Thread.sleep(500); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            log.warn("Restart requested from the administration panel: exiting with code {}", RESTART_EXIT_CODE);
            int code = SpringApplication.exit(context, () -> RESTART_EXIT_CODE);
            System.exit(code);
        }, "admin-restart");
        t.setDaemon(true);
        t.start();
        return ResponseEntity.accepted().body(Map.of("restarting", true));
    }

    private static java.util.Set<String> authorities() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        java.util.Set<String> out = new java.util.HashSet<>();
        if (a != null && a.getAuthorities() != null) a.getAuthorities().forEach(g -> out.add(g.getAuthority()));
        return out;
    }

    private static boolean restartSupported() {
        return System.getenv("INVOCATION_ID") != null;
    }

    private boolean isAdmin() {
        return adminConfig.isAdmin(SecurityContextHolder.getContext().getAuthentication());
    }

    private static String username() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a == null ? "?" : a.getName();
    }

    private ResponseEntity<Map<String, Object>> forbidden() {
        auditService.logSecurityEvent("ACCESS_DENIED", "administration");
        return ResponseEntity.status(403)
                .body(Map.of("error", "Vous n'êtes pas autorisé(e) à accéder au panneau d'administration."));
    }
}
