package eu.intracherche.intracherche.alerts.model;

import java.util.List;

public record SearchSubscription(
        String id,
        String username,
        String userEmail,
        String query,
        List<String> indices,
        String filename,
        String geoCity,
        String geoDpt,
        String geoRegion,
        String geoCountry,
        Integer dateOffsetFrom,
        Integer dateOffsetTo,
        String sort,
        NotificationFrequency frequency,
        String lastResultHash,
        Integer lastResultCount,
        Long lastCheckedAt,
        long createdAt,
        boolean isActive
) {
    public SearchSubscription withIndices(List<String> newIndices) {
        return new SearchSubscription(id, username, userEmail, query, newIndices, filename, geoCity, geoDpt,
                geoRegion, geoCountry, dateOffsetFrom, dateOffsetTo, sort, frequency, lastResultHash,
                lastResultCount, lastCheckedAt, createdAt, isActive);
    }
}
