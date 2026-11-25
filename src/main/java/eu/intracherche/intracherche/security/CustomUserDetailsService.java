package eu.intracherche.intracherche.security;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class CustomUserDetailsService implements UserDetailsService {

    @Autowired
    private SecurityService securityService; // your SID cache service

    private final LdapTemplate ldapTemplate;

    public CustomUserDetailsService(LdapTemplate ldapTemplate) {
        this.ldapTemplate = ldapTemplate;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        try {
            List<String> sids = securityService.getEffectiveSids(username);

            List<GrantedAuthority> authorities = sids.stream()
                    .map(SimpleGrantedAuthority::new)
                    .collect(Collectors.toList());


            return new org.springframework.security.core.userdetails.User(
                    username,
                    "", // password not needed for JWT flow
                    authorities
            );
        } catch (Exception e) {
            throw new UsernameNotFoundException("User not found: " + username, e);
        }
    }
}
