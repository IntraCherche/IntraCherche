package eu.intracherche.intracherche.web;

import eu.intracherche.intracherche.alerts.SubscriptionService;
import eu.intracherche.intracherche.alerts.model.NotificationFrequency;
import eu.intracherche.intracherche.alerts.model.SearchSubscription;
import eu.intracherche.intracherche.web.dto.CreateSubscriptionRequest;
import eu.intracherche.intracherche.web.dto.SubscriptionResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/subscriptions")
public class SubscriptionController {

    private final SubscriptionService subscriptionService;

    public SubscriptionController(SubscriptionService subscriptionService) {
        this.subscriptionService = subscriptionService;
    }

    @PostMapping
    public ResponseEntity<SubscriptionResponse> create(
            @RequestBody @Valid CreateSubscriptionRequest req) throws SQLException {
        String username = currentUsername();
        SearchSubscription sub = subscriptionService.createSubscription(username, req);
        return ResponseEntity.status(201).body(toResponse(sub));
    }

    @GetMapping
    public List<SubscriptionResponse> listMine() throws SQLException {
        return subscriptionService.getSubscriptionsForUser(currentUsername())
                .stream().map(this::toResponse).toList();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable("id") String id) throws SQLException {
        subscriptionService.deleteSubscription(id, currentUsername());
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{id}/frequency")
    public ResponseEntity<?> updateFrequency(
            @PathVariable("id") String id,
            @RequestBody Map<String, String> body) throws SQLException {
        NotificationFrequency freq;
        try {
            freq = NotificationFrequency.valueOf(body.get("frequency"));
        } catch (IllegalArgumentException | NullPointerException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Valeur de fréquence invalide."));
        }
        return ResponseEntity.ok(toResponse(subscriptionService.updateFrequency(id, currentUsername(), freq)));
    }

    private String currentUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth.getName();
    }

    private SubscriptionResponse toResponse(SearchSubscription s) {
        return new SubscriptionResponse(
                s.id(), s.username(), s.query(), s.indices(),
                s.filename(), s.geoCity(), s.geoDpt(), s.geoRegion(), s.geoCountry(),
                s.dateOffsetFrom(), s.dateOffsetTo(), s.sort(),
                s.frequency().name(), s.lastCheckedAt(), s.createdAt(), s.isActive()
        );
    }
}
