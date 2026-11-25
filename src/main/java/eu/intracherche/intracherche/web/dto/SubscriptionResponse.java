package eu.intracherche.intracherche.web.dto;

import java.util.List;

public record SubscriptionResponse(
        String id,
        String username,
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
        String frequency,
        Long lastCheckedAt,
        long createdAt,
        boolean isActive
) {}
