package eu.intracherche.intracherche.common.indexing;

import eu.intracherche.intracherche.config.IndexesConfig;
import eu.intracherche.intracherche.config.MountType;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public final class SMBIndexResolver implements IndexResolver {

    private final Map<String, List<String>> shareToCoreMap;

    public SMBIndexResolver(IndexesConfig indexesConfig) {
        this.shareToCoreMap = indexesConfig.getIndexes().stream()
                .flatMap(index -> index.getIndexedDirs().stream()
                        .filter(dir -> dir.getMountType() == MountType.SMB)
                        .map(dir -> Map.entry(dir.getSmbShare(), index.getCoreName())))
                .collect(Collectors.groupingBy(
                        Map.Entry::getKey,
                        Collectors.mapping(Map.Entry::getValue, Collectors.toList())
                ));
    }

    @Override
    public List<String> resolveIndexNames(Path path, String rootIdentifier) {
        // We ignore the path because on SMB, the 'rootIdentifier' (Share Name)
        // is the definitive way to know which index it belongs to.
        return shareToCoreMap.getOrDefault(rootIdentifier, Collections.emptyList());
    }
}
