package eu.intracherche.intracherche.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "admin")
public class AdminConfig {

    /**
     * SID of the Samba/AD group whose members are allowed to access the admin panel.
     * Leave empty to lock the panel for everyone (safest default — require an explicit SID to grant access).
     * Example: S-1-5-21-123456789-987654321-111111111-1234
     */
    private String adminGroupSid = "";

    public String getAdminGroupSid() { return adminGroupSid; }
    public void setAdminGroupSid(String adminGroupSid) { this.adminGroupSid = adminGroupSid; }

    /**
     * True when the authenticated user carries the admin group SID among its authorities.
     * Single source of truth for admin access: the controllers gate on it, and /api/auth/me
     * reports it to the frontend so the cogwheel is only rendered for admins.
     */
    public boolean isAdmin(Authentication auth) {
        return isAdminGroupConfigured() && hasAdminSid(auth);
    }

    /** True when an admin group SID is set; when false the panel is locked for everyone. */
    public boolean isAdminGroupConfigured() {
        return adminGroupSid != null && !adminGroupSid.isBlank();
    }

    /**
     * True when the user's token carries the configured admin SID. Reported by /api/auth/me as a
     * diagnostic: the SID itself is never exposed, only whether this session holds it.
     */
    public boolean hasAdminSid(Authentication auth) {
        if (!isAdminGroupConfigured()) return false;
        if (auth == null || auth.getAuthorities() == null) return false;
        return auth.getAuthorities().stream().anyMatch(a -> adminGroupSid.equals(a.getAuthority()));
    }
}
