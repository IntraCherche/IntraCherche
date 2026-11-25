package eu.intracherche.intracherche.config;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AdminConfig.isAdmin is the single gate behind every /api/admin endpoint and behind
 * the cogwheel the frontend renders, so the empty-SID and non-member cases matter.
 */
class AdminConfigTest {

    private static final String ADMIN_SID = "S-1-5-21-111111111-222222222-333333333-1234";

    private Authentication userWith(String... sids) {
        return new UsernamePasswordAuthenticationToken("bob", null,
                List.of(sids).stream().map(SimpleGrantedAuthority::new).toList());
    }

    private AdminConfig configWithSid(String sid) {
        AdminConfig config = new AdminConfig();
        config.setAdminGroupSid(sid);
        return config;
    }

    @Test
    void memberOfAdminGroupIsAdmin() {
        assertTrue(configWithSid(ADMIN_SID).isAdmin(userWith("S-1-5-21-999", ADMIN_SID)));
    }

    @Test
    void nonMemberIsNotAdmin() {
        assertFalse(configWithSid(ADMIN_SID).isAdmin(userWith("S-1-5-21-999")));
    }

    @Test
    void userWithNoGroupsIsNotAdmin() {
        assertFalse(configWithSid(ADMIN_SID).isAdmin(userWith()));
    }

    /** Safest default: an unconfigured SID locks the panel for everyone, not opens it. */
    @Test
    void blankAdminSidGrantsNobody() {
        assertFalse(configWithSid("").isAdmin(userWith(ADMIN_SID)));
        assertFalse(configWithSid("   ").isAdmin(userWith(ADMIN_SID)));
        assertFalse(new AdminConfig().isAdmin(userWith(ADMIN_SID)));
    }

    @Test
    void noAuthenticationIsNotAdmin() {
        assertFalse(configWithSid(ADMIN_SID).isAdmin(null));
    }

    @Test
    void diagnosticFlagsTellConfiguredFromHeld() {
        AdminConfig config = configWithSid(ADMIN_SID);
        assertTrue(config.isAdminGroupConfigured());
        assertTrue(config.hasAdminSid(userWith(ADMIN_SID)));
        assertFalse(config.hasAdminSid(userWith("S-1-5-21-999")));
        assertFalse(new AdminConfig().isAdminGroupConfigured());
        assertFalse(new AdminConfig().hasAdminSid(userWith(ADMIN_SID)));
    }
}
