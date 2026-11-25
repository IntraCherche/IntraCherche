package eu.intracherche.intracherche.web;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import eu.intracherche.intracherche.config.AdminConfig;
import eu.intracherche.intracherche.config.LlmConfig;
import eu.intracherche.intracherche.audit.AuditService;
import eu.intracherche.intracherche.security.DirectoryNames;
import eu.intracherche.intracherche.security.JwtUtils;
import eu.intracherche.intracherche.security.LdapFailureDiagnosis;
import eu.intracherche.intracherche.security.OidcLogin;
import eu.intracherche.intracherche.security.SecurityService;
import eu.intracherche.intracherche.security.SessionCookies;
import eu.intracherche.intracherche.security.SessionGuard;
import eu.intracherche.intracherche.web.dto.LoginRequest;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger logger = LoggerFactory.getLogger(AuthController.class);
    private static final int MAX_ATTEMPTS = 10;

    @Autowired
    private SessionCookies sessionCookies;

    /** Whether the "Secure cookie over plain HTTP" diagnosis was already logged: once is enough. */
    private final AtomicBoolean insecureLoginWarned = new AtomicBoolean();

    // Per-IP failure counter, reset automatically after 1 minute of inactivity.
    private final Cache<String, AtomicInteger> loginAttempts = Caffeine.newBuilder()
            .expireAfterWrite(1, TimeUnit.MINUTES)
            .build();

    @Autowired
    private AuthenticationManager authenticationManager;

    @Autowired
    private JwtUtils jwtUtils;

    @Autowired
    private AdminConfig adminConfig;

    @Autowired
    private LlmConfig llmConfig;

    @Autowired
    private SecurityService securityService;

    @Autowired
    private SessionGuard sessionGuard;

    @Autowired
    private AuditService auditService;

    @Autowired
    private OidcLogin oidcLogin;

    /**
     * {@code auth.password.enabled: false} turns the password login off for everyone, so that only single sign-on
     * remains. There is deliberately no exception, no emergency account and no special address: recovery from
     * an identity provider outage is to set it back to true in /etc/intracherche/application.yaml and restart
     * (INSTALL.md), which takes root on the server — a stronger check than any form could be.
     */
    @Value("${auth.password.enabled:true}")
    private boolean passwordEnabled = true;

    /** Refuse to start in a state where nobody could sign in, and say loudly when the password login is off. */
    @PostConstruct
    void checkSomeLoginIsPossible() {
        if (passwordEnabled) return;
        if (!oidcLogin.enabled()) {
            throw new IllegalStateException("auth.password.enabled is false while auth.oidc.enabled is false: nobody "
                    + "could sign in. Set up single sign-on first, or set auth.password.enabled back to true.");
        }
        logger.warn("Connexion par mot de passe DÉSACTIVÉE (auth.password.enabled: false) : seule la connexion unique "
                + "ouvre une session. Si le fournisseur d'identité est en panne, remettez auth.password.enabled: true "
                + "dans /etc/intracherche/application.yaml et redémarrez — voir INSTALL.md.");
    }

    /** What the login page offers: the single sign-on button (its target and text) and whether the password form is shown. */
    @GetMapping("/options")
    public Map<String, Object> options() {
        return Map.of("oidc", oidcLogin.enabled(),
                "oidcUrl", oidcLogin.loginUrl(),
                "oidcLabel", oidcLogin.buttonLabel(),
                "passwordEnabled", passwordEnabled,
                "passwordForm", passwordEnabled && !oidcLogin.passwordFormHidden());
    }

    @PostMapping("/login")
    public ResponseEntity<?> authenticateUser(@RequestBody LoginRequest loginRequest,
                                              HttpServletRequest httpRequest,
                                              HttpServletResponse httpResponse) {
        if (!passwordEnabled) {
            // Nothing is checked, not even the account: no bind to the directory, no throttle, no hint.
            auditService.logSecurityEvent(httpRequest, loginRequest.username(), "LOGIN_REFUSED",
                    "connexion par mot de passe désactivée");
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message",
                    "La connexion par mot de passe est désactivée sur ce serveur : utilisez la connexion unique."));
        }
        String clientIp = httpRequest.getRemoteAddr();
        AtomicInteger counter = loginAttempts.get(clientIp, k -> new AtomicInteger(0));
        if (counter.get() >= MAX_ATTEMPTS) {
            logger.warn("Login rate limit exceeded for IP {}", clientIp);
            auditService.logSecurityEvent(httpRequest, loginRequest.username(), "LOGIN_THROTTLED",
                    "trop de tentatives depuis cette adresse");
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("message", "Trop de tentatives de connexion. Veuillez réessayer dans une minute."));
        }

        try {
            logger.info("Authentication attempt for user: {}", loginRequest.username());

            Authentication authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(
                            loginRequest.username(),
                            loginRequest.password()
                    )
            );

            SecurityContextHolder.getContext().setAuthentication(authentication);

            // The password stamp the session is bound to: a later change or reset ends it.
            String pwdLastSet = securityService.lookupUser(authentication.getName()).pwdLastSet();

            // On the demo, whoever uses the shared demo password still gets a session of their own
            // (see OidcLogin#passwordVisitorSession); any other account signs in under its own name.
            String sessionName = authentication.getName();
            if (oidcLogin.isFixedUser(sessionName)) {
                sessionName = oidcLogin.passwordVisitorSession(clientIp);
                authentication = new UsernamePasswordAuthenticationToken(
                        new org.springframework.security.core.userdetails.User(
                                sessionName, "", authentication.getAuthorities()),
                        null, authentication.getAuthorities());
            }
            SecurityContextHolder.getContext().setAuthentication(authentication);
            String jwt = jwtUtils.generateJwtToken(authentication, pwdLastSet);

            // Deliver the JWT as an httpOnly cookie — JavaScript cannot read it,
            // which eliminates XSS-based token theft.
            // Its lifetime is taken from JwtUtils so the cookie and the token it carries always
            // expire together; jwt.expirationMinutes is the one place that duration is set.
            ResponseCookie jwtCookie = sessionCookies.session(jwt, jwtUtils.getSessionDuration());
            httpResponse.setHeader(HttpHeaders.SET_COOKIE, jwtCookie.toString());
            warnIfCookieWillBeDropped(httpRequest);

            logger.info("User '{}' authenticated successfully", sessionName);
            auditService.logSecurityEvent(httpRequest, sessionName, "LOGIN", "connexion réussie");

            // These flags let the frontend hide the admin cogwheel and the Sherlock pane
            // from users who cannot use them. UI hints only — the /api/admin and
            // /api/sherlock endpoints re-check group membership server-side.
            return ResponseEntity.ok(Map.of(
                    "username", sessionName,
                    "displayName", DirectoryNames.display(sessionName),
                    "isAdmin", adminConfig.isAdmin(authentication),
                    "canUseSherlock", llmConfig.canUseSherlock(authentication)));

        } catch (AuthenticationException e) {
            counter.incrementAndGet();
            // Log the deepest cause, not just e.getMessage(): a wrong password and an
            // unreachable/untrusted LDAPS server both surface as AuthenticationException,
            // but only the root cause (e.g. UnknownHostException, SSLHandshakeException,
            // "LDAP: error code 49") tells the admin which one it is.
            logger.error("Login failed for {}: {} — {}", loginRequest.username(),
                    e.getClass().getSimpleName(), rootCause(e));
            LdapFailureDiagnosis.of(e).ifPresent(hint -> logger.error("Directory unavailable, not a user error: {}", hint));
            logger.debug("Login failure stack trace for {}", loginRequest.username(), e);
            auditService.logSecurityEvent(httpRequest, loginRequest.username(), "LOGIN_FAILED",
                    e.getClass().getSimpleName());
            Map<String, String> body = new HashMap<>();
            body.put("message", "Invalid username or password");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(body);
        } catch (Exception e) {
            logger.error("System error during login", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Internal error");
        }
    }

    /** Unwraps the exception chain to the deepest cause for a single, diagnostic log line. */
    private static String rootCause(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.toString();
    }

    /**
     * Who am I? Re-resolves the session from the JWT cookie on every call, so the frontend
     * picks up group changes on reload instead of trusting a stale login-time flag.
     */
    @GetMapping("/me")
    public ResponseEntity<?> me() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("message", "Unauthorized", "status", 401));
        }
        return ResponseEntity.ok(Map.of(
                "username", auth.getName(),
                "displayName", DirectoryNames.display(auth.getName()),
                "isAdmin", adminConfig.isAdmin(auth),
                "adminGroupConfigured", adminConfig.isAdminGroupConfigured(),
                "adminSidInToken", adminConfig.hasAdminSid(auth),
                "canUseSherlock", llmConfig.canUseSherlock(auth)));
    }

    /**
     * Ends the session on the server too: the token is revoked, so a copy of it — not only the
     * browser's cookie — stops working now rather than when it expires.
     */
    @PostMapping("/logout")
    public ResponseEntity<?> logout(HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        String token = sessionCookies.sessionToken(httpRequest);
        if (token != null) {
            jwtUtils.parseClaims(token).ifPresent(claims -> {
                sessionGuard.revoke(claims, "déconnexion");
                auditService.logSecurityEvent(httpRequest, claims.getSubject(), "LOGOUT", "déconnexion");
            });
        }
        httpResponse.setHeader(HttpHeaders.SET_COOKIE, sessionCookies.clearedSession().toString());
        return ResponseEntity.ok().build();
    }

    /**
     * A Secure cookie sent over plain HTTP is silently dropped by the browser: the login succeeds
     * here, and the user lands back on the login page with no error anywhere. Say why, once.
     */
    private void warnIfCookieWillBeDropped(HttpServletRequest request) {
        boolean https = request.isSecure() || "https".equalsIgnoreCase(request.getHeader("X-Forwarded-Proto"));
        if (sessionCookies.secure() && !https && insecureLoginWarned.compareAndSet(false, true)) {
            logger.warn("Connexion reçue en HTTP alors que app.cookie.secure vaut true : le navigateur refuse "
                    + "le cookie de session et l'utilisateur revient à la page de connexion. Servez l'application "
                    + "en HTTPS (défaut), ou mettez app.cookie.secure: false — voir INSTALL.md, « Accès sécurisé (HTTPS) ».");
        }
    }
}
