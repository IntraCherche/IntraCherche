package eu.intracherche.intracherche.common.indexing;

import eu.intracherche.intracherche.config.IndexConfig;

import java.nio.file.Path;
import java.util.List;

/**
 * Resolves file paths to their associated index names based on configured index roots.
 * This interface defines the contract for mapping a canonical Linux file path to a list of index names
 * that should be applied to that path. The resolution process typically involves matching the path
 * against a set of configured root paths to determine which indexes are relevant.
 */
public interface IndexResolver {
    // For POSIX, 'rootIdentifier' is the base Linux path.
    // For SMB, 'rootIdentifier' is the SMB Share Name.
    List<String> resolveIndexNames(Path canonicalLinuxPath, String rootIdentifier);}