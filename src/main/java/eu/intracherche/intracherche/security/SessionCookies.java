package eu.intracherche.intracherche.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;

/**
 * Names and attributes of the session and anti-CSRF cookies, in one place.
 *
 * <p>With {@code app.cookie.secure: true} (the default, and what HTTPS needs) both cookies take
 * the {@code __Host-} prefix: the browser then only accepts them over HTTPS, from this exact host,
 * with Path=/ — a neighbouring host or a plain-HTTP page cannot plant or overwrite them. Over plain
 * HTTP ({@code app.cookie.secure: false}) the prefix is impossible and the plain names are used.
 */
@Component
public class SessionCookies {

    private final boolean secure;

    public SessionCookies(@Value("${app.cookie.secure:true}") boolean secure) {
        this.secure = secure;
    }

    public boolean secure() {
        return secure;
    }

    public String sessionName() {
        return secure ? "__Host-jwt" : "jwt";
    }

    public String csrfName() {
        return secure ? "__Host-XSRF-TOKEN" : "XSRF-TOKEN";
    }

    public ResponseCookie session(String token, Duration maxAge) {
        return ResponseCookie.from(sessionName(), token)
                .httpOnly(true)
                .secure(secure)
                .path("/")
                .maxAge(maxAge)
                .sameSite("Strict")
                .build();
    }

    public ResponseCookie clearedSession() {
        return session("", Duration.ZERO);
    }

    /** The session token carried by this request: the cookie (browsers), else the Authorization header (API clients). */
    public String sessionToken(HttpServletRequest request) {
        if (request.getCookies() != null) {
            for (Cookie cookie : request.getCookies()) {
                if (sessionName().equals(cookie.getName()) && StringUtils.hasText(cookie.getValue())) {
                    return cookie.getValue();
                }
            }
        }
        String headerAuth = request.getHeader("Authorization");
        if (StringUtils.hasText(headerAuth) && headerAuth.startsWith("Bearer ")) {
            return headerAuth.substring(7);
        }
        return null;
    }
}
