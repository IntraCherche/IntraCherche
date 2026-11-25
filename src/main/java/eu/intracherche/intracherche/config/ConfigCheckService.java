package eu.intracherche.intracherche.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;
import org.snakeyaml.engine.v2.exceptions.MarkedYamlEngineException;
import org.snakeyaml.engine.v2.exceptions.YamlEngineException;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Read-only diagnosis of the two YAML files an administrator edits by hand, application.yaml and
 * indexes.yaml. It re-reads both from disk (not from the running configuration) so that it also
 * catches an edit made after the last start: the typical lockout is a file that no longer parses,
 * found only at the next restart.
 *
 * <p>It reports what Spring would silently ignore (unknown keys, a wrongly indented block ends up
 * as an unknown key), values of the wrong type, duplicate keys, and every difference between the
 * file and the configuration the application is running with. It never writes anything and never
 * returns a secret: values of keys that look like secrets are masked.
 */
@Service
public class ConfigCheckService {

    private static final Logger log = LoggerFactory.getLogger(ConfigCheckService.class);

    public static final String ERROR = "error";
    public static final String WARNING = "warning";
    public static final String INFO = "info";

    /** Top-level sections that belong to Spring or to code reading its own keys: not checked. */
    private static final Set<String> UNCHECKED_SECTIONS =
            Set.of("app", "server", "spring", "logging", "management", "https", "debug", "springdoc", "auth");

    /** Keys the application reads that the bundled application.yaml does not list. */
    private static final Set<String> EXTRA_KNOWN_KEYS = Set.of(
            "activedirectory.urls", "activedirectory.base", "activedirectory.username", "activedirectory.password");

    private static final Set<String> INDEX_KEYS = Set.of(
            "coreName", "status", "confirmDelete", "ocr", "allowedExtensions", "visibleToGroups", "indexedDirs");
    private static final Set<String> DIR_KEYS = Set.of(
            "mountType", "smbServer", "smbShare", "smbUser", "smbPassword", "smbDomain", "linuxPath", "windowsPath");

    public record Finding(String severity, String scope, String where, String message) {}

    public record Setting(String key, String value, String defaultValue, boolean modified, boolean secret) {}

    public record IndexSummary(String coreName, String status, String visibleTo, int directories,
                               boolean ocr, boolean loaded) {}

    public record Report(String checkedAt, String applicationFile, String indexesFile,
                         List<Finding> findings, List<Setting> settings, List<IndexSummary> indexes) {}

    private final Environment env;
    private final IndexesConfig runningIndexes;

    public ConfigCheckService(Environment env, IndexesConfig runningIndexes) {
        this.env = env;
        this.runningIndexes = runningIndexes;
    }

    public Report check() {
        List<Finding> findings = new ArrayList<>();
        List<Setting> settings = new ArrayList<>();
        List<IndexSummary> indexes = new ArrayList<>();

        Path appFile = locateApplicationFile();
        Map<String, Object> bundled = loadBundledDefaults();
        Map<String, Object> bundledFlat = flatten(bundled);

        if (appFile == null) {
            findings.add(new Finding(INFO, "application.yaml", "-",
                    "Aucun fichier application.yaml externe n'est utilisé : seuls les réglages par défaut s'appliquent."));
        } else {
            checkApplicationFile(appFile, bundledFlat, findings);
        }
        collectSettings(bundledFlat, settings);
        checkAdminGroup(findings);

        String indexesPath = env.getProperty("indexing-config.indexesConfigPath");
        checkIndexesFile(indexesPath, findings, indexes);

        return new Report(Instant.now().toString(),
                appFile == null ? null : appFile.toString(), indexesPath, findings, settings, indexes);
    }

    // ── application.yaml ──────────────────────────────────────────────────────

    public Path locateApplicationFile() {
        String locations = env.getProperty("spring.config.additional-location", "");
        for (String loc : locations.split(",")) {
            String p = loc.trim().replaceFirst("^optional:", "").replaceFirst("^file:", "");
            if (!p.isEmpty() && Files.isRegularFile(Path.of(p))) return Path.of(p);
        }
        Path dev = Path.of("config", "application-local.yaml");
        return Files.isRegularFile(dev) ? dev : null;
    }

    public Map<String, Object> loadBundledDefaults() {
        try (InputStream is = ConfigCheckService.class.getClassLoader().getResourceAsStream("config/application.yaml")) {
            if (is == null) return Map.of();
            Object data = new Load(LoadSettings.builder().build()).loadFromInputStream(is);
            return asMap(data);
        } catch (IOException | RuntimeException e) {
            log.warn("Could not read the bundled application.yaml for the configuration check: {}", e.getMessage());
            return Map.of();
        }
    }

    private void checkApplicationFile(Path file, Map<String, Object> bundledFlat, List<Finding> findings) {
        Object data = parseStrict(file, "application.yaml", findings);
        if (data == null) return;
        Map<String, Object> userFlat = flatten(asMap(data));

        Map<String, String> knownByNorm = new LinkedHashMap<>();
        bundledFlat.keySet().forEach(k -> knownByNorm.put(normalize(k), k));
        EXTRA_KNOWN_KEYS.forEach(k -> knownByNorm.putIfAbsent(k, k));

        for (Map.Entry<String, Object> e : userFlat.entrySet()) {
            String key = e.getKey();
            String section = normalize(key.split("\\.")[0]);
            if (UNCHECKED_SECTIONS.contains(section)) continue;
            String norm = normalize(key);
            String known = knownByNorm.get(norm);
            if (known == null) {
                if (isUnderKnownLeaf(norm, knownByNorm.keySet())) continue;   // a map-valued setting
                findings.add(new Finding(WARNING, "application.yaml", key,
                        "Réglage inconnu : il est ignoré." + suggestion(norm, knownByNorm)
                                + " Une indentation erronée produit souvent ce cas."));
                continue;
            }
            Object def = bundledFlat.get(known);
            String typeProblem = typeProblem(def, e.getValue());
            if (typeProblem != null) {
                findings.add(new Finding(ERROR, "application.yaml", key, typeProblem));
            }
            // Edited since the last start?
            String disk = e.getValue() == null ? "" : String.valueOf(e.getValue());
            String running = env.getProperty(key);
            if (running != null && !disk.contains("${") && !running.equals(disk) && !isSecret(key)) {
                findings.add(new Finding(INFO, "application.yaml", key,
                        "Le fichier contient « " + disk + " » mais l'application fonctionne avec « " + running
                                + " » : un redémarrage est nécessaire pour appliquer la modification."));
            }
        }
    }

    private static boolean isUnderKnownLeaf(String norm, Set<String> knownNorm) {
        for (String k : knownNorm) {
            if (norm.startsWith(k + ".")) return true;
        }
        return false;
    }

    private static String suggestion(String norm, Map<String, String> knownByNorm) {
        String parent = norm.contains(".") ? norm.substring(0, norm.lastIndexOf('.')) : "";
        String best = null;
        int bestDist = 4;
        for (Map.Entry<String, String> k : knownByNorm.entrySet()) {
            String kParent = k.getKey().contains(".") ? k.getKey().substring(0, k.getKey().lastIndexOf('.')) : "";
            if (!kParent.equals(parent) && !parent.isEmpty()) continue;
            int d = levenshtein(norm, k.getKey());
            if (d < bestDist) { bestDist = d; best = k.getValue(); }
        }
        return best == null ? "" : " Vouliez-vous écrire « " + best + " » ?";
    }

    private static String typeProblem(Object def, Object value) {
        if (def == null || value == null) return null;
        if (def instanceof Boolean && !(value instanceof Boolean)) {
            return "Une valeur vrai/faux (true ou false) est attendue, « " + value + " » trouvé.";
        }
        if (def instanceof Number && !(value instanceof Number)) {
            String s = String.valueOf(value);
            if (!s.contains("${")) return "Un nombre est attendu, « " + s + " » trouvé.";
        }
        return null;
    }

    private void checkAdminGroup(List<Finding> findings) {
        String sid = env.getProperty("admin.adminGroupSid", "");
        if (sid.isBlank()) {
            findings.add(new Finding(ERROR, "application.yaml", "admin.adminGroupSid",
                    "Aucun groupe administrateur n'est défini : personne ne peut accéder au panneau d'administration."));
        }
    }

    private void collectSettings(Map<String, Object> bundledFlat, List<Setting> out) {
        for (Map.Entry<String, Object> e : bundledFlat.entrySet()) {
            String key = e.getKey();
            if (key.startsWith("app.") || key.startsWith("spring.") || key.startsWith("server.")
                    || key.startsWith("logging.")) continue;
            boolean secret = isSecret(key);
            String def = e.getValue() == null ? "" : String.valueOf(e.getValue());
            String value = env.getProperty(key);
            if (value == null) value = "";
            boolean modified = !def.contains("${") && !def.equals(value);
            out.add(new Setting(key, secret && !value.isEmpty() ? "••••••" : value,
                    secret ? "" : def, modified, secret));
        }
    }

    private static boolean isSecret(String key) {
        String k = key.toLowerCase(Locale.ROOT);
        return k.contains("password") || k.contains("secret") || k.contains("token") || k.contains("apikey");
    }

    // ── indexes.yaml ──────────────────────────────────────────────────────────

    private void checkIndexesFile(String pathStr, List<Finding> findings, List<IndexSummary> summaries) {
        if (pathStr == null || pathStr.isBlank()) {
            findings.add(new Finding(ERROR, "indexes.yaml", "-", "Le chemin du fichier des index n'est pas défini."));
            return;
        }
        Path file = Path.of(pathStr);
        if (!Files.isRegularFile(file)) {
            findings.add(new Finding(INFO, "indexes.yaml", pathStr,
                    "Fichier absent du disque (configuration fournie par l'application) : contrôle impossible."));
            summarizeRunning(summaries);
            return;
        }
        Object data = parseStrict(file, "indexes.yaml", findings);
        if (data == null) { summarizeRunning(summaries); return; }

        List<?> items = checkIndexesRoot(asMap(data), findings);
        if (items == null) { summarizeRunning(summaries); return; }
        summarizeIndexes(items, summaries);
    }

    /**
     * Checks the content of an indexes file already parsed (also used on a candidate before it is
     * saved). Returns the list of indexes, or null when there is none to go on with.
     */
    public List<?> checkIndexesRoot(Map<String, Object> root, List<Finding> findings) {
        for (String k : root.keySet()) {
            if (!"indexes".equals(k)) {
                findings.add(new Finding(WARNING, "indexes.yaml", k,
                        "Clé inconnue au premier niveau : elle est ignorée. Seule « indexes » est attendue."));
            }
        }
        Object list = root.get("indexes");
        if (!(list instanceof List<?> items)) {
            findings.add(new Finding(ERROR, "indexes.yaml", "indexes",
                    list == null ? "La liste « indexes » est absente : aucun index ne sera créé."
                            : "« indexes » doit être une liste (chaque index commence par « - coreName: ... »)."));
            return null;
        }
        if (items.isEmpty()) {
            findings.add(new Finding(WARNING, "indexes.yaml", "indexes", "La liste des index est vide."));
        }
        Set<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < items.size(); i++) {
            Object item = items.get(i);
            String label = "index n°" + (i + 1);
            if (!(item instanceof Map<?, ?>)) {
                findings.add(new Finding(ERROR, "indexes.yaml", label, "Cet élément n'est pas un index (clé: valeur attendue)."));
                continue;
            }
            Map<String, Object> idx = asMap(item);
            Object core = idx.get("coreName");
            String name = core == null ? null : String.valueOf(core);
            if (name == null || name.isBlank()) {
                findings.add(new Finding(ERROR, "indexes.yaml", label, "« coreName » est absent : cet index est inutilisable."));
                name = label;
            } else if (!seen.add(name)) {
                findings.add(new Finding(ERROR, "indexes.yaml", name, "Nom d'index en double : les noms doivent être uniques."));
            }
            checkIndex(name, idx, findings);
        }
        return items;
    }

    private void checkIndex(String name, Map<String, Object> idx, List<Finding> findings) {
        for (String k : idx.keySet()) {
            if (!INDEX_KEYS.contains(k)) {
                findings.add(new Finding(WARNING, "indexes.yaml", name + " › " + k,
                        "Réglage inconnu : il est ignoré." + suggestionExact(k, INDEX_KEYS)
                                + " Vérifiez l'indentation : un index commence par « - coreName: »."));
            }
        }
        if (idx.get("visibleToGroups") == null) {
            findings.add(new Finding(ERROR, "indexes.yaml", name + " › visibleToGroups",
                    "Obligatoire : écrire « ALL » ou la liste des groupes autorisés. L'application ne démarre pas sans."));
        }
        Object status = idx.get("status");
        if (status != null && !Set.of("ACTIVE", "PAUSED", "DELETE").contains(String.valueOf(status).trim().toUpperCase(Locale.ROOT))) {
            findings.add(new Finding(ERROR, "indexes.yaml", name + " › status",
                    "Valeur « " + status + " » inconnue (active, paused ou delete) : l'index sera mis en pause."));
        }
        if ("DELETE".equalsIgnoreCase(String.valueOf(status)) && !Boolean.TRUE.equals(idx.get("confirmDelete"))) {
            findings.add(new Finding(WARNING, "indexes.yaml", name + " › status",
                    "Suppression demandée sans « confirmDelete: true » : l'index reste en pause."));
        }
        if (idx.get("ocr") != null && !(idx.get("ocr") instanceof Boolean)) {
            findings.add(new Finding(ERROR, "indexes.yaml", name + " › ocr", "true ou false attendu."));
        }
        Object dirs = idx.get("indexedDirs");
        if (!(dirs instanceof List<?> dirList) || dirList.isEmpty()) {
            findings.add(new Finding(WARNING, "indexes.yaml", name + " › indexedDirs",
                    "Aucun dossier à indexer : cet index restera vide."));
            return;
        }
        for (int j = 0; j < dirList.size(); j++) {
            String where = name + " › dossier n°" + (j + 1);
            if (!(dirList.get(j) instanceof Map<?, ?>)) {
                findings.add(new Finding(ERROR, "indexes.yaml", where, "Entrée invalide (clé: valeur attendue)."));
                continue;
            }
            Map<String, Object> dir = asMap(dirList.get(j));
            for (String k : dir.keySet()) {
                if (!DIR_KEYS.contains(k)) {
                    findings.add(new Finding(WARNING, "indexes.yaml", where + " › " + k,
                            "Réglage inconnu : il est ignoré." + suggestionExact(k, DIR_KEYS)));
                }
            }
            for (String required : List.of("smbServer", "smbShare", "smbUser", "smbPassword")) {
                if (dir.get(required) == null || String.valueOf(dir.get(required)).isBlank()) {
                    findings.add(new Finding(ERROR, "indexes.yaml", where + " › " + required, "Valeur manquante."));
                }
            }
            if ("CHANGE_ME".equals(String.valueOf(dir.get("smbPassword")))) {
                findings.add(new Finding(ERROR, "indexes.yaml", where + " › smbPassword",
                        "Le mot de passe d'exemple « CHANGE_ME » n'a pas été remplacé."));
            }
        }
    }

    private static String suggestionExact(String key, Set<String> known) {
        String best = null;
        int bestDist = 4;
        for (String k : known) {
            int d = levenshtein(key.toLowerCase(Locale.ROOT), k.toLowerCase(Locale.ROOT));
            if (d < bestDist) { bestDist = d; best = k; }
        }
        return best == null ? "" : " Vouliez-vous écrire « " + best + " » ?";
    }

    private void summarizeIndexes(List<?> items, List<IndexSummary> out) {
        Map<String, IndexConfig> running = new LinkedHashMap<>();
        if (runningIndexes != null && runningIndexes.getIndexes() != null) {
            runningIndexes.getIndexes().forEach(c -> running.put(c.getCoreName(), c));
        }
        Set<String> onDisk = new LinkedHashSet<>();
        for (Object item : items) {
            if (!(item instanceof Map<?, ?>)) continue;
            Map<String, Object> idx = asMap(item);
            String name = idx.get("coreName") == null ? "?" : String.valueOf(idx.get("coreName"));
            onDisk.add(name);
            IndexConfig c = running.get(name);
            if (c != null) {
                out.add(new IndexSummary(name, c.getStatus().name(), visibility(c),
                        c.getIndexedDirs() == null ? 0 : c.getIndexedDirs().size(), c.isOcr(), true));
            } else {
                out.add(new IndexSummary(name, String.valueOf(idx.getOrDefault("status", "ACTIVE")).toUpperCase(Locale.ROOT),
                        "", idx.get("indexedDirs") instanceof List<?> l ? l.size() : 0,
                        !Boolean.FALSE.equals(idx.get("ocr")), false));
            }
        }
        for (IndexConfig c : running.values()) {
            if (!onDisk.contains(c.getCoreName())) {
                out.add(new IndexSummary(c.getCoreName(), c.getStatus().name(), visibility(c),
                        c.getIndexedDirs() == null ? 0 : c.getIndexedDirs().size(), c.isOcr(), true));
            }
        }
    }

    private void summarizeRunning(List<IndexSummary> out) {
        if (runningIndexes == null || runningIndexes.getIndexes() == null) return;
        for (IndexConfig c : runningIndexes.getIndexes()) {
            out.add(new IndexSummary(c.getCoreName(), c.getStatus().name(), visibility(c),
                    c.getIndexedDirs() == null ? 0 : c.getIndexedDirs().size(), c.isOcr(), true));
        }
    }

    private static String visibility(IndexConfig c) {
        return c.isVisibleToAll() ? "ALL" : String.join(", ", c.getVisibleToGroups());
    }

    // ── YAML helpers ──────────────────────────────────────────────────────────

    /** Parses with duplicate keys refused (the default silently keeps the last one). */
    private Object parseStrict(Path file, String scope, List<Finding> findings) {
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return new Load(LoadSettings.builder().setAllowDuplicateKeys(false).build()).loadFromReader(r);
        } catch (MarkedYamlEngineException e) {
            String at = e.getProblemMark().map(m -> " (ligne " + (m.getLine() + 1) + ", colonne " + (m.getColumn() + 1) + ")").orElse("");
            findings.add(new Finding(ERROR, scope, file.toString(),
                    "Le fichier est invalide" + at + " : " + e.getProblem()
                            + ". L'application ne démarrera pas, ou ne lira pas ce fichier, au prochain redémarrage."));
        } catch (YamlEngineException | IOException e) {
            findings.add(new Finding(ERROR, scope, file.toString(), "Le fichier ne peut pas être lu : " + e.getMessage()));
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        if (!(o instanceof Map<?, ?> m)) return Map.of();
        Map<String, Object> out = new LinkedHashMap<>();
        m.forEach((k, v) -> out.put(String.valueOf(k), v));
        return out;
    }

    public static Map<String, Object> flatten(Map<String, Object> map) {
        Map<String, Object> out = new LinkedHashMap<>();
        flattenInto("", map, out);
        return out;
    }

    private static void flattenInto(String prefix, Map<String, Object> map, Map<String, Object> out) {
        for (Map.Entry<String, Object> e : map.entrySet()) {
            String key = prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey();
            if (e.getValue() instanceof Map<?, ?> m && !m.isEmpty()) flattenInto(key, asMap(m), out);
            else out.put(key, e.getValue());
        }
    }

    /** Spring binds keys regardless of case, dashes and underscores. */
    private static String normalize(String key) {
        return key.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
    }

    static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev; prev = cur; cur = t;
        }
        return prev[b.length()];
    }
}
