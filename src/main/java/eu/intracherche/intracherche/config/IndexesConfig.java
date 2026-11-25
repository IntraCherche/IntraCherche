package eu.intracherche.intracherche.config;

import java.util.Collection;
import java.util.List;

public class IndexesConfig {

    private List<IndexConfig> indexes;

    public IndexesConfig() {} // REQUIRED

    /**
     * The cores among {@code cores} a user holding {@code userSids} may see, in the order given.
     * A core indexes.yaml does not declare is seen by nobody: no rule says who it is for.
     */
    public List<String> visibleCores(Collection<String> cores, Collection<String> userSids) {
        return cores.stream().filter(core -> isVisible(core, userSids)).toList();
    }

    public boolean isVisible(String coreName, Collection<String> userSids) {
        if (indexes == null || coreName == null) return false;
        return indexes.stream()
                .filter(index -> coreName.equals(index.getCoreName()))
                .findFirst()
                .map(index -> index.isVisibleTo(userSids))
                .orElse(false);
    }

    public List<IndexConfig> getIndexes() {
        return indexes;
    }

    /**
     * Sets the list of index configurations.
     *
     * @param indexes a list of index configuration objects
     */
    public void setIndexes(List<IndexConfig> indexes) {
        this.indexes = indexes;
    }
}


