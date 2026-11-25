package eu.intracherche.intracherche.security.identity;

import java.util.Optional;
import java.util.Set;

public record UserIdentity(
        String username,
        Optional<String> domain,
        Set<String> groups
) {}

