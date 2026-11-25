package eu.intracherche.intracherche.indexing;

import java.util.Locale;
import java.util.Set;

/**
 * What to tell an administrator whose files exhaust the application's memory: how far the heap can
 * reasonably be raised on this server, and from which size the documents that cost the most memory
 * could be left out. Pure arithmetic, so the advice is the same in the log, in the admin panel and
 * in the tests.
 */
public final class MemoryHints {

    private MemoryHints() {}

    /** The heap is never advised beyond this share of the server's RAM: Solr, the analysis services and the OS need the rest. */
    static final double MAX_HEAP_SHARE_OF_RAM = 0.25;

    /** The sizes (MB) offered for indexing-config.maxFileSizeMb, so that the advice is a round number. */
    private static final long[] SIZE_STEPS_MB = {8, 16, 32, 64, 128, 256};

    /** Formats whose whole content is read as a tree in memory: their cost grows with their size. */
    private static final Set<String> SIZE_DRIVEN = Set.of(
            "xlsx", "xlsm", "xls", "ods", "docx", "doc", "odt", "pptx", "ppt", "odp");

    /**
     * The heap to advise, in MB, or -1 when the current one is already as large as this server
     * should give it. Twice the current heap, within a quarter of the RAM, in whole GB.
     */
    public static long suggestedHeapMb(long currentHeapMb, long totalRamMb) {
        if (totalRamMb <= 0) return -1;
        long ceiling = (long) (totalRamMb * MAX_HEAP_SHARE_OF_RAM) / 1024 * 1024;
        long wanted = Math.min(currentHeapMb * 2, ceiling) / 1024 * 1024;
        return wanted > currentHeapMb ? wanted : -1;
    }

    /**
     * The largest of the usual sizes (MB) that leaves this file out, or -1 when the file's cost
     * does not come from its size (a scanned PDF costs by its pages) or is too small for a size
     * limit to be a sensible answer.
     */
    public static long sizeLimitExcludingMb(String path, long sizeBytes) {
        if (!sizeDriven(path)) return -1;
        long sizeMb = sizeBytes / (1024 * 1024);
        long best = -1;
        for (long step : SIZE_STEPS_MB) {
            if (step < sizeMb) best = step;
        }
        return best;
    }

    static boolean sizeDriven(String path) {
        if (path == null) return false;
        int dot = path.lastIndexOf('.');
        return dot >= 0 && SIZE_DRIVEN.contains(path.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    /** The sentence(s) of advice for a file that crashed the application, in French as the panel is. */
    public static String advice(String path, long sizeBytes, long currentHeapMb, long totalRamMb) {
        StringBuilder sb = new StringBuilder();
        long heap = suggestedHeapMb(currentHeapMb, totalRamMb);
        if (heap > 0) {
            sb.append(String.format("Pour l'indexer : portez la mémoire de l'application à %d Go "
                    + "(IC_HEAP_MB=%d dans /etc/intracherche/jvm.conf).", heap / 1024, heap));
        } else {
            sb.append("La mémoire de l'application est déjà au maximum conseillé pour ce serveur : "
                    + "seul un ajout de mémoire permettrait de l'indexer.");
        }
        long limit = sizeLimitExcludingMb(path, sizeBytes);
        if (limit > 0) {
            sb.append(String.format(" Pour exclure ce type de fichiers volumineux : indexing-config.maxFileSizeMb: %d.", limit));
        }
        return sb.toString();
    }
}
