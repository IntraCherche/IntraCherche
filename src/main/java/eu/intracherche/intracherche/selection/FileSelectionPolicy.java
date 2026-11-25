package eu.intracherche.intracherche.selection;

import eu.intracherche.intracherche.registry.FileRecord;

import java.util.List;
import java.util.Optional;

public interface FileSelectionPolicy {
    Optional<FileRecord> selectNext(List<FileRecord> candidates);
}
