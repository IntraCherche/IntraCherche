package eu.intracherche.intracherche.web.dto;

import eu.intracherche.intracherche.alerts.model.NotificationFrequency;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record CreateSubscriptionRequest(
        String query,
        @NotEmpty List<String> indices,
        String filename,
        String geoCity,
        String geoDpt,
        String geoRegion,
        String geoCountry,
        Integer dateOffsetFrom,
        Integer dateOffsetTo,
        String sort,
        NotificationFrequency frequency
) {}
