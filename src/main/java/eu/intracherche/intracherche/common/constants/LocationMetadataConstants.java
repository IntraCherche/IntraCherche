package eu.intracherche.intracherche.common.constants;

public enum LocationMetadataConstants {
    GEO_CITIES("geo.cities"),
    GEO_DEPARTMENTS("geo.departments"),
    GEO_REGIONS("geo.regions"),
    GEO_COUNTRIES("geo.countries");

    private final String key;

    LocationMetadataConstants(String key) {
        this.key = key;
    }

    public String getKey() {
        return key;
    }
}
