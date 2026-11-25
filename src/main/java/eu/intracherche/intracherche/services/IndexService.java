package eu.intracherche.intracherche.services;

import eu.intracherche.intracherche.config.IndexConfig;
import eu.intracherche.intracherche.config.IndexesConfig;
import eu.intracherche.intracherche.config.IndexedDirConfig;
import eu.intracherche.intracherche.config.ConfigurationException;
import eu.intracherche.intracherche.registry.SmbLookupKey;
import eu.intracherche.intracherche.web.SmbCredentials;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Service for managing SMB credentials cache.
 * This service builds a cache of SMB credentials from the index configuration
 * at application startup and provides methods to retrieve credentials by core name, server, and share.
 */
@Service
public class IndexService {
    
    // Mark as final since you only load on restart
    private final Map<SmbLookupKey, SmbCredentials> credentialMap;

    public IndexService(IndexesConfig config) {
        this.credentialMap = Collections.unmodifiableMap(buildCache(config));
    }

    private Map<SmbLookupKey, SmbCredentials> buildCache(IndexesConfig config) {
        Map<SmbLookupKey, SmbCredentials> tempMap = new HashMap<>();
        // Temporary map to track: "Server+Share" -> "Credentials"
        Map<String, SmbCredentials> globalShareCheck = new HashMap<>();

        for (IndexConfig index : config.getIndexes()) {
            for (IndexedDirConfig dir : index.getIndexedDirs()) {
                if (dir.isSmb()) {
                    String sharePath = dir.getSmbServer() + "/" + dir.getSmbShare();
                    SmbCredentials currentCreds = new SmbCredentials(dir.getSmbUser(), dir.getSmbPassword(), "");

                    // Check for global consistency
                    if (globalShareCheck.containsKey(sharePath) &&
                            !globalShareCheck.get(sharePath).equals(currentCreds)) {
                        System.out.println("On a les credentials suivants " + currentCreds.toString() + " et le globalShareCheck " + globalShareCheck.toString());
                        throw new IllegalStateException(String.format(
                                "CONFIRMATION ERROR: Share [%s] is defined with conflicting credentials " +
                                        "between cores. Please unify the passwords in indexes.yaml.", sharePath));
                    }
                    globalShareCheck.put(sharePath, currentCreds);

                    // Map to our SmbLookupKey for runtime lookups
                    SmbLookupKey key = new SmbLookupKey(index.getCoreName(), dir.getSmbServer(), dir.getSmbShare());
                    tempMap.put(key, currentCreds);
                }
            }
        }
        return tempMap;
    }

    /**
     * Retrieves credentials or throws an exception if the configuration is missing.
     */
    public SmbCredentials getSmbCredentialsFor(String coreName, String server, String share)
            throws ConfigurationException {

        SmbLookupKey key = new SmbLookupKey(coreName, server, share);
        SmbCredentials creds = credentialMap.get(key);

        if (creds == null) {
            throw new ConfigurationException(String.format(
                    "CRITICAL CONFIG ERROR: No SMB credentials found in indexes.yaml for " +
                            "[Core: %s, Server: %s, Share: %s]. Check your YAML syntax and core names.",
                    coreName, server, share
            ));
        }

        return creds;
    }
    
    /**
     * Check if SMB credentials exist for a specific core, server, and share.
     */
    public boolean hasSmbCredentials(String coreName, String server, String share) {
        return credentialMap.containsKey(new SmbLookupKey(coreName, server, share));
    }
}
