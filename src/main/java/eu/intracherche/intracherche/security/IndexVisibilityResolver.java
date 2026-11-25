package eu.intracherche.intracherche.security;

import eu.intracherche.intracherche.config.IndexConfig;
import eu.intracherche.intracherche.config.IndexesConfig;
import eu.intracherche.intracherche.config.IndexesConfigException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Turns the group names of indexes.yaml's {@code visibleToGroups} into SIDs, once, at startup.
 *
 * <p>Names keep the file readable; SIDs are what the user's token carries, and they survive a
 * rename. Resolving at startup gives both: a group renamed in AD while the app runs keeps its
 * index, and the next start refuses the old name instead of silently matching nothing.
 *
 * <p>Any doubt stops the startup: a name AD does not know, an account that is not a group, a
 * directory that cannot be read. Each index is logged with the groups as AD spells them and
 * their SIDs, so a group given to the wrong index shows in plain words.
 */
public final class IndexVisibilityResolver {

    private static final Logger log = LoggerFactory.getLogger(IndexVisibilityResolver.class);

    private IndexVisibilityResolver() {}

    public static void resolve(IndexesConfig config, Function<String, List<DirectoryGroup>> directory) {
        if (config.getIndexes() == null) return;
        Map<String, DirectoryGroup> resolved = new HashMap<>();
        for (IndexConfig index : config.getIndexes()) {
            String core = index.getCoreName();
            if (index.isVisibleToAll()) {
                log.info("Index '{}' is visible to every user", core);
                continue;
            }
            Map<String, String> sids = new LinkedHashMap<>();
            for (String name : index.getVisibleToGroups()) {
                DirectoryGroup group = resolved.computeIfAbsent(name, n -> lookup(n, core, directory));
                sids.put(group.name(), group.sid());
            }
            index.setVisibleToGroupSids(sids);
            log.info("Index '{}' is visible only to members of {}", core, sids);
        }
    }

    private static DirectoryGroup lookup(String name, String core,
                                         Function<String, List<DirectoryGroup>> directory) {
        List<DirectoryGroup> found;
        try {
            found = directory.apply(name);
        } catch (RuntimeException e) {
            throw fail("Could not read Active Directory to check the group '" + name + "' in visibleToGroups of index '"
                    + core + "' (" + e.getMessage() + "). IntraCherche does not start until it can check who may "
                    + "see this index.", e);
        }
        if (found.isEmpty()) {
            throw fail("Group name '" + name + "' for setting visibleToGroups of index '" + core
                    + "' does not exist in AD. Please carefully check this name (the group's pre-Windows 2000 name) "
                    + "and correct it before starting up again.", null);
        }
        if (found.size() > 1) {
            throw fail("Group name '" + name + "' for setting visibleToGroups of index '" + core
                    + "' matches " + found.size() + " entries in AD. Please give a name that designates a single group.", null);
        }
        DirectoryGroup group = found.get(0);
        if (!group.group()) {
            throw fail("'" + name + "' in visibleToGroups of index '" + core + "' is not a group in AD (it is a user "
                    + "or another kind of account). Only groups can be given: correct it before starting up again.", null);
        }
        if (group.sid() == null) {
            throw fail("Group '" + name + "' in visibleToGroups of index '" + core + "' has no SID in AD.", null);
        }
        return group;
    }

    private static IndexesConfigException fail(String message, Throwable cause) {
        return new IndexesConfigException(message, cause);
    }
}
