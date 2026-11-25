package eu.intracherche.intracherche.config;

import jakarta.validation.constraints.NotNull;
import eu.intracherche.intracherche.config.Status;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Configuration class representing the settings for a single index.
 */
public class IndexConfig {

    public static final Set<String> DEFAULT_ALLOWED_EXTENSIONS = Set.of(
            "pdf", "doc", "docx", "odt",
            "xls", "xlsx", "ods",
            "ppt", "pptx", "odp",
            "eml", "msg",
            "txt"
    );

    @NotNull
    private String coreName;

    @NotNull
    private List<IndexedDirConfig> indexedDirs;

    // New fields for status control
    private Status status = Status.ACTIVE;  // Default to ACTIVE
    private boolean confirmDelete = false;  // Default to false
    private boolean ocr = true;            // OCR enabled by default

    // null means "use DEFAULT_ALLOWED_EXTENSIONS"
    private List<String> allowedExtensions = null;

    public Set<String> resolvedAllowedExtensions() {
        if (allowedExtensions == null || allowedExtensions.isEmpty()) {
            return DEFAULT_ALLOWED_EXTENSIONS;
        }
        return Set.copyOf(allowedExtensions);
    }

    // visibleToGroups: required in indexes.yaml, either ALL or a list of AD group names.
    private boolean visibleToAll = false;
    private List<String> visibleToGroups = List.of();
    // The groups as AD spells them, with their SIDs: filled at startup by
    // IndexVisibilityResolver. Null until then, and a restricted index with no SIDs is
    // visible to nobody, so a config that skipped the resolution fails closed.
    private Map<String, String> visibleToGroupSids = null;

    /**
     * Whether a user holding these SIDs may see this index: list it, search it, ask Sherlock
     * about it, open its documents. A restriction on top of the files' NTFS rights, never a
     * grant: inside a visible index, each document is still filtered by its accessTokens.
     */
    public boolean isVisibleTo(Collection<String> userSids) {
        if (visibleToAll) return true;
        if (visibleToGroupSids == null || userSids == null) return false;
        return visibleToGroupSids.values().stream().anyMatch(userSids::contains);
    }

    public boolean isVisibleToAll() { return visibleToAll; }
    public void setVisibleToAll(boolean visibleToAll) { this.visibleToAll = visibleToAll; }
    public List<String> getVisibleToGroups() { return visibleToGroups; }
    public void setVisibleToGroups(List<String> visibleToGroups) { this.visibleToGroups = List.copyOf(visibleToGroups); }
    public Map<String, String> getVisibleToGroupSids() { return visibleToGroupSids; }
    public void setVisibleToGroupSids(Map<String, String> sids) { this.visibleToGroupSids = sids; }

    public List<String> getAllowedExtensions() { return allowedExtensions; }
    public void setAllowedExtensions(List<String> allowedExtensions) { this.allowedExtensions = allowedExtensions; }

    public List<IndexedDirConfig> getIndexedDirs() {        return indexedDirs;    }
    /**
     * Sets the list of platform-specific directories to be indexed.
     *
     * @param indexedDirs a list of PlatformPath objects representing the directories
     *                    to be indexed, with separate paths for Linux and Windows platforms
     */
    public void setIndexedDirs(List<IndexedDirConfig> indexedDirs) {        this.indexedDirs = indexedDirs;    }

    public String getCoreName() {        return coreName;    }
    public void setCoreName(String coreName) {        this.coreName = coreName;    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public boolean isConfirmDelete() {
        return confirmDelete;
    }

    public void setConfirmDelete(boolean confirmDelete) {
        this.confirmDelete = confirmDelete;
    }

    public boolean isOcr() {
        return ocr;
    }

    public void setOcr(boolean ocr) {
        this.ocr = ocr;
    }

}

