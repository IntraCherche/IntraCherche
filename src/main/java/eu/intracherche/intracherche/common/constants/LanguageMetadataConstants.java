package eu.intracherche.intracherche.common.constants;

/**
 * Constants for language detection metadata keys
 */
public enum LanguageMetadataConstants {
    LANG_PRIMARY("lang.primary"),
    LANG_CONFIDENCE("lang.confidence");

    private final String key;

    LanguageMetadataConstants(String key) {
        this.key = key;
    }

    public String getKey() {
        return key;
    }
}
