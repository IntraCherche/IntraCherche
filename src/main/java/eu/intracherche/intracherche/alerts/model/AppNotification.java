package eu.intracherche.intracherche.alerts.model;

import java.util.UUID;

public record AppNotification(
        String id,
        String username,
        String subscriptionId,
        String message,
        String querySummary,
        long createdAt,
        boolean isRead,
        String notificationType
) {
    public static final String TYPE_SEARCH_ALERT = "SEARCH_ALERT";
    public static final String TYPE_SHERLOCK_ANSWER = "SHERLOCK_ANSWER";

    public static AppNotification forSearchAlert(String id, String username, String subscriptionId,
                                                  String message, String querySummary, long createdAt) {
        return new AppNotification(id, username, subscriptionId, message, querySummary, createdAt, false, TYPE_SEARCH_ALERT);
    }

    public static AppNotification forSherlockAnswer(String username, String questionId,
                                                     String message, long createdAt) {
        return new AppNotification(UUID.randomUUID().toString(), username, questionId,
                message, null, createdAt, false, TYPE_SHERLOCK_ANSWER);
    }
}
