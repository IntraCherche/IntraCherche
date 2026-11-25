package eu.intracherche.intracherche.web;

import eu.intracherche.intracherche.alerts.dao.NotificationDao;
import eu.intracherche.intracherche.alerts.model.AppNotification;
import eu.intracherche.intracherche.web.dto.NotificationResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationDao notificationDao;

    public NotificationController(NotificationDao notificationDao) {
        this.notificationDao = notificationDao;
    }

    @GetMapping
    public List<NotificationResponse> list(
            @RequestParam(name = "limit", defaultValue = "20") int limit,
            @RequestParam(name = "offset", defaultValue = "0") int offset) throws SQLException {
        return notificationDao.findByUsername(currentUsername(), limit, offset)
                .stream().map(this::toResponse).toList();
    }

    @GetMapping("/unread-count")
    public Map<String, Integer> unreadCount() throws SQLException {
        return Map.of("count", notificationDao.countUnread(currentUsername()));
    }

    @PatchMapping("/{id}/read")
    public ResponseEntity<Void> markRead(@PathVariable("id") String id) throws SQLException {
        notificationDao.markRead(id, currentUsername());
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/read-all")
    public ResponseEntity<Void> markAllRead() throws SQLException {
        notificationDao.markAllRead(currentUsername());
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/sherlock/read-all")
    public ResponseEntity<Void> markSherlockNotificationsRead() throws SQLException {
        notificationDao.markReadByType(currentUsername(), AppNotification.TYPE_SHERLOCK_ANSWER);
        return ResponseEntity.noContent().build();
    }

    private String currentUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth.getName();
    }

    private NotificationResponse toResponse(AppNotification n) {
        return new NotificationResponse(n.id(), n.subscriptionId(), n.message(), n.querySummary(),
                n.createdAt(), n.isRead(), n.notificationType());
    }
}
