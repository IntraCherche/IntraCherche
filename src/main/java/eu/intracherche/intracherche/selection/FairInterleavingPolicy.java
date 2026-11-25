package eu.intracherche.intracherche.selection;

import eu.intracherche.intracherche.registry.FileRecord;

import java.util.*;

/**
 * A file selection policy that implements fair interleaving across multiple indexes.
 * This policy ensures that files from different indexes are selected in a round-robin fashion,
 * providing balanced processing across all indexes while maintaining fairness.
 *
 * The selection process works by:
 * 1. Grouping candidate files by their index membership
 * 2. Performing a round-robin selection across these index groups
 * 3. Maintaining an internal cursor to track the starting point for the next selection
 * 4. Returning the first available file from the next index in the rotation
 *
 * Files are selected in a thread-safe manner using synchronized access to the selection method.
 * If no candidates are provided or if no files are available from any index, an empty Optional is returned.
 *
 * The policy prioritizes fairness over other factors such as file age or size,
 * ensuring that each index receives approximately equal attention during file selection.
 *
 * Please note : a FIleRecord can have multiple indexes to which it belongs. In that case for performance
 * we want to parse it only once and then dispatch it to the matching indexes.
 */
public class FairInterleavingPolicy implements FileSelectionPolicy {

    private int cursor = 0;

    @Override
    public synchronized Optional<FileRecord> selectNext(List<FileRecord> candidates) {

        // 1. Build per-index queues
        Map<String, Queue<FileRecord>> perIndexQueues = new LinkedHashMap<>();

        for (FileRecord file : candidates) {
            for (String index : file.indexMembershipActual()) {
                perIndexQueues
                        .computeIfAbsent(index, k -> new ArrayDeque<>())
                        .add(file);
            }
        }

        if (perIndexQueues.isEmpty()) {
            return Optional.empty();
        }

        // 2. Fair round-robin across indexes
        // CAUTION : we want the same index order on every call
        List<String> indexNames = perIndexQueues.keySet().stream()
                .sorted()
                .toList();

        for (int i = 0; i < indexNames.size(); i++) {
            int idx = (cursor + i) % indexNames.size();
            String indexName = indexNames.get(idx);
            Queue<FileRecord> queue = perIndexQueues.get(indexName);

            if (!queue.isEmpty()) {
                cursor = (idx + 1) % indexNames.size();
                return Optional.of(queue.poll());
            }
        }

        return Optional.empty();
    }
}


