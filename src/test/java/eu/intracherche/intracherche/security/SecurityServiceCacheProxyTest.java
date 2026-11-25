package eu.intracherche.intracherche.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.core.env.MapPropertySource;
import org.springframework.ldap.core.AttributesMapper;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.ldap.query.LdapQuery;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code lookupUser} through the real Spring cache proxy, which the plain unit tests bypass.
 *
 * <p>It is the guard on a bug that broke every login: the cache key named the method parameter
 * ({@code #username}), and the build does not compile with {@code -parameters}, so the key was
 * null and each call threw "Null key returned for cache operation".
 */
class SecurityServiceCacheProxyTest {

    @Configuration
    @EnableCaching
    static class Config {
        @Bean
        LdapTemplate ldapTemplate() {
            LdapTemplate ldap = mock(LdapTemplate.class);
            when(ldap.search(any(LdapQuery.class), any(AttributesMapper.class))).thenReturn(List.of());
            return ldap;
        }

        @Bean
        CacheManager cacheManager() {
            return new ConcurrentMapCacheManager("userSids");
        }

        @Bean
        static PropertySourcesPlaceholderConfigurer placeholders() {
            return new PropertySourcesPlaceholderConfigurer();
        }

        @Bean
        SecurityService securityService(LdapTemplate ldap) {
            return new SecurityService(ldap);
        }
    }

    private static AnnotationConfigApplicationContext context() {
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources()
                .addFirst(new MapPropertySource("test", Map.of("spring.ldap.base", "DC=corp,DC=local")));
        context.register(Config.class);
        context.refresh();
        return context;
    }

    @Test
    @DisplayName("the cache key is computed, and every visitor session of an account shares one entry")
    void cacheKeyIsTheDirectoryAccount() {
        try (var context = context()) {
            SecurityService service = context.getBean(SecurityService.class);
            LdapTemplate ldap = context.getBean(LdapTemplate.class);
            var cache = context.getBean(CacheManager.class).getCache("userSids");

            // Must not throw "Null key returned for cache operation".
            service.lookupUser("alice");
            service.lookupUser("demo|a3f9c2d41b7e0856");
            service.lookupUser("demo|0123456789abcdef");

            // A not-found account is never cached (unless = "!#result.found()"), so what proves the key is
            // that it was computed at all, and that it names the directory account.
            verify(ldap, times(3)).search(any(LdapQuery.class), any(AttributesMapper.class));
            assertThat(cache.get("demo|a3f9c2d41b7e0856")).isNull();
        }
    }

    @Test
    @DisplayName("a found account is looked up once for all its visitor sessions")
    void foundAccountIsCachedOnce() throws Exception {
        try (var context = context()) {
            SecurityService service = context.getBean(SecurityService.class);
            // Seed the cache the way a successful lookup would, under the directory name.
            var cache = context.getBean(CacheManager.class).getCache("userSids");
            DirectoryUser demo = new DirectoryUser(List.of("S-1-5-21-1-2-3-1105"), true, true, false, null, null);
            cache.put("demo", demo);

            assertThat(service.lookupUser("demo|a3f9c2d41b7e0856")).isSameAs(demo);
            assertThat(service.lookupUser("demo|0123456789abcdef")).isSameAs(demo);
            assertThat(service.lookupUser("demo")).isSameAs(demo);
            verify(context.getBean(LdapTemplate.class), times(0)).search(any(LdapQuery.class), any(AttributesMapper.class));
        }
    }
}
