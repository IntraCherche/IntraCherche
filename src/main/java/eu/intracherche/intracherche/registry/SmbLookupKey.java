package eu.intracherche.intracherche.registry;

import java.util.Objects;

/**
 * Unique identifier for an SMB share within a specific Solr core.
 */
public record SmbLookupKey(
    String coreName, 
    String server, 
    String share
) {
    public SmbLookupKey {
        // Validation to ensure we don't map nulls into our lookup table
        Objects.requireNonNull(coreName, "Core name cannot be null");
        Objects.requireNonNull(server, "Server cannot be null");
        Objects.requireNonNull(share, "Share cannot be null");
    }
}