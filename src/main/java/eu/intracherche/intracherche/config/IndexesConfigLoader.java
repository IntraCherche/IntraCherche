package eu.intracherche.intracherche.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public final class IndexesConfigLoader {

    private static final Logger log = LoggerFactory.getLogger(IndexesConfigLoader.class);

    private IndexesConfigLoader() {}

    public static IndexesConfig load(String resourcePath) {
        // Using snakeyaml-engine API - simpler approach
        LoadSettings settings = LoadSettings.builder().build();
        Load yaml = new Load(settings);
        Object data;
        try (InputStream is = openConfig(resourcePath)) {
            data = yaml.loadFromInputStream(is);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read indexes config at " + resourcePath, e);
        }

        // Handle the case where data might be null or not a Map
        if (data instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> mapData = (Map<String, Object>) data;
            return convertToIndexesConfig(mapData);
        }

        return new IndexesConfig(); // Return empty config if no data
    }

    /**
     * Opens the indexes config, resolving the path against the filesystem first
     * (production installs point at an absolute path like /etc/intracherche/indexes.yaml)
     * and falling back to the classpath (the bundled default used during development).
     */
    private static InputStream openConfig(String path) {
        Path fsPath = Path.of(path);
        if (Files.isRegularFile(fsPath)) {
            try {
                return Files.newInputStream(fsPath);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to open indexes config at " + path, e);
            }
        }

        InputStream is = IndexesConfigLoader.class.getClassLoader().getResourceAsStream(path);
        if (is == null) {
            throw new IllegalStateException(
                    "Indexes config not found at " + path + " (checked filesystem and classpath)");
        }
        return is;
    }

    private static IndexesConfig convertToIndexesConfig(Map<String, Object> data) {
        IndexesConfig config = new IndexesConfig();
        if (data != null && data.containsKey("indexes")) {
            @SuppressWarnings("unchecked")
            java.util.List<Map<String, Object>> indexesList = (java.util.List<Map<String, Object>>) data.get("indexes");
            if (indexesList != null) {
                java.util.List<IndexConfig> indexConfigs = new java.util.ArrayList<>();
                for (Map<String, Object> indexData : indexesList) {
                    IndexConfig indexConfig = new IndexConfig();
                    // Set coreName
                    if (indexData.containsKey("coreName")) {
                        indexConfig.setCoreName((String) indexData.get("coreName"));
                    }
                    // Set indexedDirs
                    if (indexData.containsKey("indexedDirs")) {
                        @SuppressWarnings("unchecked")
                        java.util.List<Map<String, Object>> dirsList = (java.util.List<Map<String, Object>>) indexData.get("indexedDirs");
                        if (dirsList != null) {
                            java.util.List<IndexedDirConfig> dirConfigs = new java.util.ArrayList<>();
                            for (Map<String, Object> dirData : dirsList) {
                                if (dirData == null) continue;
                                IndexedDirConfig dirConfig = new IndexedDirConfig();
                                // Set mountType
                                if (dirData.containsKey("mountType")) {
                                    String mount = String.valueOf(dirData.get("mountType"));
                                    try {
                                        dirConfig.setMountType(MountType.valueOf(mount));
                                    } catch (IllegalArgumentException e) {
                                        throw new IllegalArgumentException("mountType '" + mount
                                                + "' is not supported: only SMB network shares can be indexed", e);
                                    }
                                }
                                // Set linuxPath
                                if (dirData.containsKey("linuxPath")) {
                                    dirConfig.setLinuxPath((String) dirData.get("linuxPath"));
                                }
                                // Set windowsPath
                                if (dirData.containsKey("windowsPath")) {
                                    dirConfig.setWindowsPath((String) dirData.get("windowsPath"));
                                }
                                // Set smbServer
                                if (dirData.containsKey("smbServer")) {
                                    dirConfig.setSmbServer((String) dirData.get("smbServer"));
                                }
                                // Set smbShare
                                if (dirData.containsKey("smbShare")) {
                                    dirConfig.setSmbShare((String) dirData.get("smbShare"));
                                }
                                // Set smbUser
                                if (dirData.containsKey("smbUser")) {
                                    dirConfig.setSmbUser((String) dirData.get("smbUser"));
                                }
                                // Set smbPassword
                                if (dirData.containsKey("smbPassword")) {
                                    dirConfig.setSmbPassword((String) dirData.get("smbPassword"));
                                }
                                // Set smbDomain
                                if (dirData.containsKey("smbDomain")) {
                                    dirConfig.setSmbDomain((String) dirData.get("smbDomain"));
                                }
                                dirConfigs.add(dirConfig);
                            }
                            indexConfig.setIndexedDirs(dirConfigs);
                        }
                    }
                    
                    // Set status (with default Status.ACTIVE)
                    if (indexData.containsKey("status")) {
                        indexConfig.setStatus(parseStatus(indexData.get("status"), indexConfig.getCoreName()));
                    } else {
                        indexConfig.setStatus(Status.ACTIVE);
                    }
                    
                    // Set confirmDelete (with default false)
                    if (indexData.containsKey("confirmDelete")) {
                        indexConfig.setConfirmDelete((Boolean) indexData.get("confirmDelete"));
                    } else {
                        indexConfig.setConfirmDelete(false);
                    }
                    // DELETE without its confirmation used to carry on as ACTIVE: an index the
                    // admin had asked to remove kept being fed. Hold it as PAUSED until either
                    // confirmDelete: true or a change of mind, as for a mistyped status.
                    if (indexConfig.getStatus() == Status.DELETE && !indexConfig.isConfirmDelete()) {
                        log.warn("Index '{}': status DELETE without confirmDelete: true; the index is PAUSED "
                                + "(neither fed nor deleted) until the deletion is confirmed.", indexConfig.getCoreName());
                        indexConfig.setStatus(Status.PAUSED);
                    }

                    // Set ocr (with default true)
                    if (indexData.containsKey("ocr")) {
                        indexConfig.setOcr((Boolean) indexData.get("ocr"));
                    }

                    // Set allowedExtensions (null/absent means use DEFAULT_ALLOWED_EXTENSIONS).
                    // Normalise to lower-case/trimmed so matching is case-insensitive.
                    if (indexData.containsKey("allowedExtensions")) {
                        @SuppressWarnings("unchecked")
                        java.util.List<String> rawExts = (java.util.List<String>) indexData.get("allowedExtensions");
                        if (rawExts != null) {
                            java.util.List<String> exts = new java.util.ArrayList<>();
                            for (String ext : rawExts) {
                                if (ext != null && !ext.isBlank()) {
                                    exts.add(ext.trim().toLowerCase());
                                }
                            }
                            indexConfig.setAllowedExtensions(exts);
                        }
                    }
                    parseVisibleToGroups(indexData, indexConfig);
                    indexConfigs.add(indexConfig);
                }
                
                // Validate that all coreNames are unique
                java.util.Set<String> coreNames = new java.util.HashSet<>();
                for (IndexConfig indexConfig : indexConfigs) {
                    String coreName = indexConfig.getCoreName();
                    if (coreName != null) {
                        if (!coreNames.add(coreName)) {
                            throw new IllegalStateException("Duplicate coreName found in indexes configuration: '" + coreName + "'. All coreNames must be unique.");
                        }
                    }
                }
                
                config.setIndexes(indexConfigs);
            }
        }
        return config;
    }

    static final String VISIBLE_TO_ALL = "ALL";

    /**
     * {@code visibleToGroups} is required on every index, with no default: a forgotten line
     * would otherwise decide on its own who sees the index. {@code ALL} is every user; a group
     * name or a list of them restricts the index to their members, checked against AD at
     * startup (see IndexVisibilityResolver).
     */
    static void parseVisibleToGroups(Map<String, Object> indexData, IndexConfig indexConfig) {
        String core = indexConfig.getCoreName();
        Object raw = indexData.get("visibleToGroups");
        if (raw == null) {
            throw new IndexesConfigException("Index '" + core + "': visibleToGroups is missing in the indexes config. "
                    + "Write 'visibleToGroups: ALL' to let every user see this index, or list the AD groups "
                    + "whose members may see it, e.g. 'visibleToGroups: [Medical]'.");
        }
        java.util.List<String> names = new java.util.ArrayList<>();
        if (raw instanceof java.util.List<?> list) {
            for (Object item : list) {
                String name = item == null ? "" : item.toString().trim();
                if (name.isEmpty()) {
                    throw new IndexesConfigException("Index '" + core + "': visibleToGroups holds an empty group name.");
                }
                names.add(name);
            }
        } else {
            names.add(raw.toString().trim());
        }
        boolean all = names.stream().anyMatch(VISIBLE_TO_ALL::equalsIgnoreCase);
        if (all && names.size() > 1) {
            throw new IndexesConfigException("Index '" + core + "': visibleToGroups mixes ALL with group names "
                    + names + ". Write either ALL or the list of groups.");
        }
        if (names.isEmpty() || names.get(0).isEmpty()) {
            throw new IndexesConfigException("Index '" + core + "': visibleToGroups is empty. "
                    + "Write ALL, or the AD groups whose members may see this index.");
        }
        indexConfig.setVisibleToAll(all);
        indexConfig.setVisibleToGroups(all ? java.util.List.of() : names);
    }

    /**
     * An unknown status pauses the index and says so. It used to fall back to ACTIVE
     * silently, so a typo did the opposite of what was written: "status: DELETED" (the
     * value is DELETE) kept an index being fed and searched. PAUSED neither indexes nor
     * deletes anything until the typo is fixed.
     */
    static Status parseStatus(Object raw, String coreName) {
        String value = raw == null ? "" : raw.toString().trim();
        try {
            return Status.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            log.error("Index '{}': unknown status '{}' in the indexes config (expected one of {}); "
                            + "the index is PAUSED until it is corrected.",
                    coreName, value, java.util.Arrays.toString(Status.values()));
            return Status.PAUSED;
        }
    }
}
