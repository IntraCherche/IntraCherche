package eu.intracherche.intracherche.common.events;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Set;


public record FileDiscoveredEvent(
            Path path,// Local mount path OR a virtual SMB path
            String smbShare,       // The name of the share (null for POSIX)
            String smbHost,        // The SMB server host name (null for POSIX)
            long size,
            Instant lastModified,
            Instant lastSeen,
            Set<String> allowSids, // Renamed from accessTokens for clarity
            Set<String> denySids,  // Added to handle Windows "Deny" ACEs
            String accessTokensHash,
            boolean isSmb          // Helper for the IndexingService
    ) {}
