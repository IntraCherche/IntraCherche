package eu.intracherche.intracherche.security;

import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * security.sidCacheTtlMinutes: how long a user's group SIDs are reused before AD is read again.
 * INSTALL.md: "0 pour relire l'annuaire à chaque requête".
 */
class SidCacheTtlTest {

    @Test
    void sids_are_reused_within_the_configured_minutes() {
        Cache cache = userSidsCache(15);

        cache.put("alice", "S-1-5-21-1");

        assertThat(cache.get("alice")).isNotNull();
    }

    @Test
    void zero_reads_the_directory_on_every_request() {
        Cache cache = userSidsCache(0);

        cache.put("alice", "S-1-5-21-1");

        assertThat(cache.get("alice")).as("nothing kept: the next request reads AD").isNull();
    }

    private static Cache userSidsCache(long ttlMinutes) {
        LdapSecurityConfig config = new LdapSecurityConfig();
        ReflectionTestUtils.setField(config, "sidCacheTtlMinutes", ttlMinutes);
        CacheManager manager = config.cacheManager();
        return manager.getCache("userSids");
    }
}
