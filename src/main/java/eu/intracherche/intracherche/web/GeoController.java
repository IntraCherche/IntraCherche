package eu.intracherche.intracherche.web;

import eu.intracherche.intracherche.services.NerService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/geo")
public class GeoController {

    private final NerService nerService;

    public GeoController(NerService nerService) {
        this.nerService = nerService;
    }

    /** Choices for the advanced-search geo filters; a 503 makes the UI fall back to free text. */
    @GetMapping("/gazetteer")
    public ResponseEntity<NerService.Gazetteer> gazetteer() {
        return nerService.getGazetteer()
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build());
    }
}
