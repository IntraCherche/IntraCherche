package eu.intracherche.intracherche.web.dto;

public record NotificationResponse(
        String id,
        String subscriptionId,
        String message,
        String querySummary,
        long createdAt,
        boolean isRead,
        String notificationType
) {}
