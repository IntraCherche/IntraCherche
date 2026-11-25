package eu.intracherche.intracherche.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Utility class to help with access control in search operations
 */
@Component
public class SearchAccessControl {
    private static final Logger log = LoggerFactory.getLogger(SearchAccessControl.class);


    /**
     * Get the access rights/authorities of the currently logged-in user
     * @return List of user authorities/roles
     */
    public List<String> getCurrentUserAuthorities() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return Collections.emptyList();
        }

        List<String> authorities = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toList());

        // DEBUG: This will show you exactly why Alice is failing.
        log.debug("DEBUG: User {} has Authorities: {}", auth.getName(), authorities);

        return authorities;
    }

    /**
     * Check if the current user has a specific authority/role
     * @param authority the authority to check
     * @return true if user has the authority, false otherwise
     */
    public boolean hasAuthority(String authority) {
        List<String> authorities = getCurrentUserAuthorities();
        return authorities.contains(authority);
    }

    /**
     * Check if the current user has any of the required authorities
     * @param requiredAuthorities list of required authorities
     * @return true if user has at least one of the required authorities, false otherwise
     */
    public boolean hasAnyAuthority(List<String> requiredAuthorities) {
        List<String> userAuthorities = getCurrentUserAuthorities();
        for (String requiredAuthority : requiredAuthorities) {
            if (userAuthorities.contains(requiredAuthority)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Filter search results based on user access rights
     * This is an example of how to implement access control in search results
     * @param results the original search results
     * @return filtered results based on user access rights
     */
    public List<Map<String, Object>> filterResultsByAccessRights(List<Map<String, Object>> results) {
        // Example implementation - in a real app, you would implement proper access control logic
        // For now, we'll just return all results as an example
        return results;
    }
}
