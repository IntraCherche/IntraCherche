package eu.intracherche.intracherche.security.acl.provider;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;

/**
 * Provider is used during file scanning to get file access rights
 */
public interface AccessTokenProvider {
    Set<String> extractAccessTokens(Path path) throws IOException;

    /**
     * SIDs explicitly denied read access, checked in addition to (never instead of) the allow
     * tokens above. Default empty: a provider with no deny concept (POSIX's additive-allow model)
     * denies nothing beyond "not in the allow set".
     */
    default Set<String> extractDenyTokens(Path path) throws IOException {
        return Set.of();
    }
}

