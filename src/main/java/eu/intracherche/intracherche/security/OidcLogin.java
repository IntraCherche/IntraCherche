package eu.intracherche.intracherche.security;

import eu.intracherche.intracherche.audit.AuditService;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Sign in with an OpenID Connect provider. Off unless {@code auth.oidc.enabled}; the password login is
 * untouched and keeps working. Two modes ({@code auth.oidc.mode}):
 *
 * <ul>
 *   <li>{@code directory} — for a company. The provider's identity (a claim of the ID token: the e-mail,
 *       the UPN…) is turned into one directory account by looking the claim's value up in one attribute of
 *       the directory ({@code mail}, {@code userPrincipalName}…). Exactly one account must match; none, or
 *       several, is refused. The session is then the account's own, named like a password login's, with the
 *       account's SIDs, so the quota, the history and the audit are the same whichever way the user came in.</li>
 *   <li>{@code demo-fixed} — for the public demo. Every visitor with a verified Google identity signs in as
 *       the one account {@code auth.oidc.fixedUser}, told apart from the others only by a session name
 *       {@code <account>|<visitor>} (see {@link DirectoryNames}); the visitor id is a keyed hash of the issuer
 *       and subject, so nothing personal is stored. It is never the default: it runs only when
 *       {@code auth.oidc.mode} says {@code demo-fixed} in so many words, so "everyone is the same account"
 *       cannot be turned on by mistake.</li>
 * </ul>
 *
 * <p>Spring checks the signature, issuer, audience, expiry and nonce of the ID token, and the state and PKCE of
 * the exchange. What is checked here is what Spring cannot know: that the issuer is the one expected (for Entra
 * ID, the right tenant), that the identity is complete and, where it matters, that the e-mail is verified, and
 * which directory account it is.
 */
@Component
public class OidcLogin implements AuthenticationSuccessHandler, AuthenticationFailureHandler {

    private static final Logger log = LoggerFactory.getLogger(OidcLogin.class);
    static final String DEMO_FIXED = "demo-fixed";
    static final String DIRECTORY = "directory";
    private static final String GOOGLE = "https://accounts.google.com";
    private static final Pattern ATTRIBUTE_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9-]*");
    /** Where the browser lands after a refused login; the login page shows the message. */
    static final String FAILURE_URL = "/login?oidcError=1";

    private final boolean enabled;
    private final String mode;
    private final String registrationId;
    private final String fixedUser;
    private final String expectedIssuer;
    private final String directoryClaim;
    private final String directoryAttribute;
    private final boolean requireVerifiedEmail;
    private final String buttonLabel;
    private final boolean passwordFormHidden;
    private final String visitorKey;

    private final SecurityService securityService;
    private final JwtUtils jwtUtils;
    private final SessionCookies sessionCookies;
    private final AuditService auditService;

    @Autowired
    public OidcLogin(@Value("${auth.oidc.enabled:false}") boolean enabled,
                     @Value("${auth.oidc.mode:directory}") String mode,
                     @Value("${auth.oidc.registrationId:google}") String registrationId,
                     @Value("${auth.oidc.fixedUser:demo}") String fixedUser,
                     @Value("${auth.oidc.issuer:}") String issuer,
                     @Value("${auth.oidc.directory.claim:email}") String directoryClaim,
                     @Value("${auth.oidc.directory.attribute:mail}") String directoryAttribute,
                     @Value("${auth.oidc.directory.requireVerifiedEmail:}") String requireVerifiedEmail,
                     @Value("${auth.oidc.buttonLabel:}") String buttonLabel,
                     @Value("${auth.passwordForm.hidden:false}") boolean passwordFormHidden,
                     @Value("${jwt.secret}") String jwtSecret,
                     SecurityService securityService, JwtUtils jwtUtils, SessionCookies sessionCookies,
                     AuditService auditService) {
        this.enabled = enabled;
        this.mode = mode;
        this.registrationId = registrationId;
        this.fixedUser = fixedUser;
        this.expectedIssuer = (issuer == null || issuer.isBlank()) && DEMO_FIXED.equals(mode) ? GOOGLE : issuer;
        this.directoryClaim = directoryClaim;
        this.directoryAttribute = directoryAttribute;
        // An e-mail is only an identity once the provider says it checked it. Other claims (upn,
        // preferred_username) come with no such flag, and Entra ID does not send email_verified at all.
        this.requireVerifiedEmail = requireVerifiedEmail == null || requireVerifiedEmail.isBlank()
                ? "email".equals(directoryClaim) : Boolean.parseBoolean(requireVerifiedEmail.trim());
        this.buttonLabel = buttonLabel != null && !buttonLabel.isBlank() ? buttonLabel
                : DIRECTORY.equals(mode) ? "Se connecter avec l'authentification unique" : "Se connecter avec Google";
        this.passwordFormHidden = passwordFormHidden;
        this.visitorKey = "visitor-id:" + jwtSecret;
        this.securityService = securityService;
        this.jwtUtils = jwtUtils;
        this.sessionCookies = sessionCookies;
        this.auditService = auditService;
    }

    @PostConstruct
    void validate() {
        if (!enabled) return;
        if (registrationId == null || registrationId.isBlank() || !ATTRIBUTE_NAME.matcher(registrationId).matches()) {
            throw new IllegalStateException("auth.oidc.registrationId must be the id of a spring.security.oauth2.client.registration; got '"
                    + registrationId + "'");
        }
        switch (mode) {
            case DEMO_FIXED -> {
                if (fixedUser == null || fixedUser.isBlank() || fixedUser.indexOf('|') >= 0) {
                    throw new IllegalStateException("auth.oidc.fixedUser must be a directory account name without '|'");
                }
                log.info("OpenID Connect login enabled ({}): every verified visitor signs in as '{}'", mode, fixedUser);
            }
            case DIRECTORY -> {
                if (expectedIssuer == null || expectedIssuer.isBlank()) {
                    throw new IllegalStateException("auth.oidc.issuer is required in directory mode: the exact issuer of the "
                            + "provider's tokens (for Entra ID: https://login.microsoftonline.com/<tenant-id>/v2.0)");
                }
                if (directoryClaim == null || directoryClaim.isBlank()) {
                    throw new IllegalStateException("auth.oidc.directory.claim must name the token claim that identifies the user");
                }
                if (directoryAttribute == null || !ATTRIBUTE_NAME.matcher(directoryAttribute).matches()) {
                    throw new IllegalStateException("auth.oidc.directory.attribute must be a directory attribute name "
                            + "(mail, userPrincipalName…); got '" + directoryAttribute + "'");
                }
                log.info("OpenID Connect login enabled ({}): issuer {}, claim '{}' matched against the directory attribute '{}'{}",
                        mode, expectedIssuer, directoryClaim, directoryAttribute,
                        requireVerifiedEmail ? ", e-mail must be verified" : "");
            }
            default -> throw new IllegalStateException("auth.oidc.mode must be '" + DEMO_FIXED + "' or '" + DIRECTORY
                    + "'; got '" + mode + "'");
        }
    }

    public boolean enabled() { return enabled; }

    /** Where the login page sends the browser to start the sign-in. */
    public String loginUrl() { return "/oauth2/authorization/" + registrationId; }

    /** The text of the sign-in button. */
    public String buttonLabel() { return buttonLabel; }

    /** Whether {@code account} is the one every Google visitor signs in as (AD names ignore case); demo mode only. */
    public boolean isFixedUser(String account) {
        return enabled && DEMO_FIXED.equals(mode) && fixedUser.equalsIgnoreCase(account);
    }

    /**
     * The session name of a visitor who logs in with the demo account's password: the account plus an id
     * derived from the client address, so that two prospects do not share one quota and one question
     * history, and one prospect cannot start afresh by logging out and in again.
     */
    public String passwordVisitorSession(String clientIp) {
        return DirectoryNames.visitor(fixedUser, visitorId("password", clientIp));
    }

    /** Hide the user/password form from the login page (the endpoint itself stays, with its throttle). */
    public boolean passwordFormHidden() { return enabled && passwordFormHidden; }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        endHandshake(request, response);
        if (!(authentication.getPrincipal() instanceof OidcUser oidc)) {
            refuse(request, response, "réponse sans identité OpenID Connect");
            return;
        }
        String reason = commonRefusal(oidc);
        if (reason != null) {
            refuse(request, response, reason);
            return;
        }
        Login login = DEMO_FIXED.equals(mode) ? demoLogin(oidc) : directoryLogin(oidc);
        if (login.refusal() != null) {
            refuse(request, response, login.refusal());
            return;
        }

        // The account's SIDs, and its state: a disabled, expired or missing account opens no session.
        DirectoryUser account = securityService.lookupUser(login.account());
        var denial = account.denialReason(Instant.now());
        if (denial.isPresent() || !account.reachable()) {
            refuse(request, response, "compte « " + login.account() + " » inutilisable : "
                    + denial.orElse("annuaire injoignable"));
            return;
        }
        List<GrantedAuthority> authorities = account.sids().stream()
                .<GrantedAuthority>map(SimpleGrantedAuthority::new).toList();
        Authentication session = new UsernamePasswordAuthenticationToken(
                new User(login.sessionName(), "", authorities), null, authorities);

        String jwt = jwtUtils.generateJwtToken(session, account.pwdLastSet());
        // addHeader, not setHeader: the anti-CSRF cookie of this very response is another Set-Cookie, and
        // setHeader would silently replace it.
        response.addHeader(HttpHeaders.SET_COOKIE,
                sessionCookies.session(jwt, jwtUtils.getSessionDuration()).toString());
        auditService.logSecurityEvent(request, login.sessionName(), "LOGIN", login.detail());
        response.sendRedirect("/");
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                        AuthenticationException exception) throws IOException {
        endHandshake(request, response);
        // Spring's message names the step that failed ("[invalid_token_response] … I/O error on POST request for
        // …/token"), and the deepest cause says why (an untrusted TLS certificate, an unreachable provider).
        Throwable root = exception;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        refuse(request, response, exception.getMessage() + (root != exception ? " — " + root : ""));
    }

    /** What decides the directory account and the session name, or why there is none. */
    private record Login(String account, String sessionName, String detail, String refusal) {
        static Login refused(String reason) { return new Login(null, null, null, reason); }
    }

    /** Why this identity must not open a session whatever the mode; null when it may. */
    private String commonRefusal(OidcUser oidc) {
        if (!enabled) return "connexion OpenID Connect désactivée";
        if (oidc.getIssuer() == null || !expectedIssuer.equals(oidc.getIssuer().toString())) {
            return "émetteur inattendu : " + oidc.getIssuer();
        }
        if (oidc.getSubject() == null || oidc.getSubject().isBlank()) return "identité sans sujet";
        return null;
    }

    /** Demo: any verified Google identity is a visitor of the one demo account. */
    private Login demoLogin(OidcUser oidc) {
        if (!Boolean.TRUE.equals(oidc.getEmailVerified())) return Login.refused("adresse e-mail non vérifiée");
        String visitor = visitorId(oidc.getIssuer().toString(), oidc.getSubject());
        return new Login(fixedUser, DirectoryNames.visitor(fixedUser, visitor), "connexion réussie (OpenID Connect, démo)", null);
    }

    /** Company: the claim's value is looked up in the directory and must point at exactly one account. */
    private Login directoryLogin(OidcUser oidc) {
        String value = oidc.getClaimAsString(directoryClaim);
        if (value == null || value.isBlank()) {
            return Login.refused("le jeton ne contient pas la revendication « " + directoryClaim + " »");
        }
        if (requireVerifiedEmail && !Boolean.TRUE.equals(oidc.getEmailVerified())) {
            return Login.refused("adresse e-mail non vérifiée (" + value + ")");
        }
        SecurityService.AccountMatch match = securityService.findAccounts(directoryAttribute, value.trim());
        if (!match.reachable()) return Login.refused("annuaire injoignable");
        if (match.accounts().isEmpty()) {
            return Login.refused("aucun compte ne correspond à " + directoryAttribute + "=" + value);
        }
        if (match.accounts().size() > 1) {
            return Login.refused("plusieurs comptes correspondent à " + directoryAttribute + "=" + value);
        }
        String account = match.accounts().get(0);
        return new Login(account, account, "connexion réussie (OpenID Connect)", null);
    }

    String visitorId(String issuer, String subject) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(visitorKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal((issuer + "\n" + subject).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    /**
     * The login handshake lived in a short server session; it is not needed once the login ends.
     *
     * <p>The session is thrown away, and so is its cookie: Spring Security changes the session id when someone
     * authenticates (protection against session fixation), so this response also carries the cookie of the new
     * id, which points to a session that is already gone. Expiring it here leaves no dead cookie in the browser.
     * The attributes are those Tomcat uses, as it only replaces a cookie with the same name, path and scope.
     */
    private void endHandshake(HttpServletRequest request, HttpServletResponse response) {
        HttpSession session = request.getSession(false);
        if (session != null) session.invalidate();
        SecurityContextHolder.clearContext();
        String name = request.getServletContext().getSessionCookieConfig().getName();
        String contextPath = request.getContextPath();
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(name != null ? name : "JSESSIONID", "")
                .path(contextPath == null || contextPath.isEmpty() ? "/" : contextPath)
                .httpOnly(true).secure(sessionCookies.secure()).sameSite("Lax").maxAge(Duration.ZERO).build().toString());
    }

    private void refuse(HttpServletRequest request, HttpServletResponse response, String reason) throws IOException {
        log.warn("OpenID Connect login refused: {}", reason);
        auditService.logSecurityEvent(request, null, "LOGIN_FAILED", "OpenID Connect : " + reason);
        // Whoever asked to sign in as someone else is not the previous user any more: a refusal must not
        // leave that session open behind the login page. (The callback is a cross-site navigation, so the
        // browser does not send the old session cookie and it cannot be revoked here; the login page does
        // that with a logout call, which carries it.)
        response.addHeader(HttpHeaders.SET_COOKIE, sessionCookies.clearedSession().toString());
        response.sendRedirect(FAILURE_URL);
    }
}
