package eu.intracherche.intracherche.security;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.ldap.core.ContextSource;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.ldap.core.support.BaseLdapPathContextSource;
import org.springframework.ldap.core.support.LdapContextSource;

import java.util.Map;
import java.util.concurrent.TimeUnit;

@Configuration
@EnableCaching
@Primary  // Tells Spring: "Use mine, ignore the AutoConfiguration one"
public class LdapSecurityConfig {

    @Value("${spring.ldap.urls}")
    private String ldapUrl;

    @Value("${spring.ldap.base}")
    private String ldapBase;

    @Value("${spring.ldap.username}")
    private String ldapUser;

    @Value("${spring.ldap.password}")
    private String ldapPassword;

    /**
     * How long a user's resolved SID set is reused before it is read from AD again.
     * This is the delay with which an AD group change takes effect in IntraCherche, so it
     * trades promptness against load: the set is needed on every authenticated request, not
     * only at login. 0 disables the cache (every request hits the DC).
     */
    @Value("${security.sidCacheTtlMinutes:15}")
    private long sidCacheTtlMinutes;

    @Bean
    public BaseLdapPathContextSource contextSource() {
        LdapContextSource source = new LdapContextSource();
        source.setUrl(ldapUrl);
//        source.setBase(ldapBase);
        source.setUserDn(ldapUser);
        source.setPassword(ldapPassword);
        // Important: This handles the binary mapping for you
        source.setBaseEnvironmentProperties(Map.of(
                "java.naming.ldap.attributes.binary", "objectSid tokenGroups sIDHistory",
                "java.naming.referral", "ignore"
        ));
        return source;
    }

    @Bean
    public LdapTemplate ldapTemplate(ContextSource contextSource) {
        LdapTemplate template = new LdapTemplate(contextSource);
        template.setIgnorePartialResultException(true);
        return template;
    }

    // Caffeine Configuration — backs @Cacheable("userSids") on SecurityService.getEffectiveSids.
    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager("userSids");
        cacheManager.setCaffeine(Caffeine.newBuilder()
                .expireAfterWrite(Math.max(0, sidCacheTtlMinutes), TimeUnit.MINUTES)
                .maximumSize(1000));
        return cacheManager;
    }
}