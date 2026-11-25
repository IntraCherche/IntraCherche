package eu.intracherche.intracherche.config;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LlmConfig.canUseSherlock gates both the /api/sherlock endpoints and the pane the frontend
 * renders. Its blank-SID default is the opposite of AdminConfig's on purpose, so it is pinned here.
 */
class LlmConfigAccessTest {

    private static final String SHERLOCK_SID = "S-1-5-21-111111111-222222222-333333333-4321";

    private Authentication userWith(String... sids) {
        return new UsernamePasswordAuthenticationToken("bob", null,
                List.of(sids).stream().map(SimpleGrantedAuthority::new).toList());
    }

    private LlmConfig configWithSid(String sid) {
        LlmConfig config = new LlmConfig();
        config.setSherlockGroupSid(sid);
        return config;
    }

    @Test
    void memberOfSherlockGroupIsAllowed() {
        assertTrue(configWithSid(SHERLOCK_SID).canUseSherlock(userWith("S-1-5-21-999", SHERLOCK_SID)));
    }

    @Test
    void nonMemberIsRefused() {
        assertFalse(configWithSid(SHERLOCK_SID).canUseSherlock(userWith("S-1-5-21-999")));
        assertFalse(configWithSid(SHERLOCK_SID).canUseSherlock(userWith()));
    }

    /** Opposite of the admin default: an unconfigured SID opens Sherlock to everyone. */
    @Test
    void blankSherlockSidAllowsEveryone() {
        assertTrue(configWithSid("").canUseSherlock(userWith("S-1-5-21-999")));
        assertTrue(configWithSid("   ").canUseSherlock(userWith()));
        assertTrue(new LlmConfig().canUseSherlock(userWith()));
    }

    /** llm.enabled=false is the switch that turns Sherlock off for everybody, group or not. */
    @Test
    void disabledLlmRefusesEveryone() {
        LlmConfig withGroup = configWithSid(SHERLOCK_SID);
        withGroup.setEnabled(false);
        assertFalse(withGroup.canUseSherlock(userWith(SHERLOCK_SID)));

        LlmConfig openToAll = configWithSid("");
        openToAll.setEnabled(false);
        assertFalse(openToAll.canUseSherlock(userWith("S-1-5-21-999")));
    }

    @Test
    void noAuthenticationIsRefusedWhenAGroupIsConfigured() {
        assertFalse(configWithSid(SHERLOCK_SID).canUseSherlock(null));
    }
}
