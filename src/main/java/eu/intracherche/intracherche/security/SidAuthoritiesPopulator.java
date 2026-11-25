package eu.intracherche.intracherche.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ldap.core.DirContextOperations;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.ldap.userdetails.LdapAuthoritiesPopulator;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class SidAuthoritiesPopulator implements LdapAuthoritiesPopulator {

    private static final Logger log = LoggerFactory.getLogger(SidAuthoritiesPopulator.class);

    /** Above this many SIDs the access filter of a search runs to several KB. */
    static final int LARGE_SID_SET = 150;

    private final SecurityService securityService;

    public SidAuthoritiesPopulator(SecurityService securityService) {
        this.securityService = securityService;
    }

    @Override
    public Collection<? extends GrantedAuthority> getGrantedAuthorities(
            DirContextOperations userData, String username) {

        // 1. Fetch the real SIDs (Primary, tokenGroups, sIDHistory) from your Service
        // This is the step that uses the Cache we set up earlier.
        List<String> sids = new ArrayList<>(securityService.getEffectiveSids(username));

        // 2. Add "Well-Known" SIDs (Universal constants in every Windows/Samba Env)
        // These are often missing from 'tokenGroups' but present in File ACLs.

        // S-1-1-0 = "Everyone" (Important for public/shared files)
        if (!sids.contains("S-1-1-0")) sids.add("S-1-1-0");

        // S-1-5-11 = "Authenticated Users" (Alice is logged in, so she is one)
        if (!sids.contains("S-1-5-11")) sids.add("S-1-5-11");

        // The count only, never the username or the SIDs: it is what tells support whether a
        // user's access filter is abnormally large.
        if (sids.size() > LARGE_SID_SET) {
            log.warn("Login: {} SIDs in the user's token; every search carries them as filters of about {} KB",
                    sids.size(), sids.size() * 100 / 1024 + 1);
        } else {
            log.info("Login: {} SIDs in the user's token", sids.size());
        }

        // 3. Convert these String SIDs into Spring Security Authorities (GrantedAuthority)
        // This allows auth.getAuthorities() to return the SID list for Solr filtering.
        return sids.stream()
                .map(SimpleGrantedAuthority::new)
                .collect(Collectors.toList());
    }
}
