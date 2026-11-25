package eu.intracherche.intracherche.services;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.intracherche.intracherche.config.NerConfig;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

@Service
public class NerService {

    private static final Logger log = LoggerFactory.getLogger(NerService.class);
    private static final MediaType TEXT_PLAIN_UTF8 = new MediaType("text", "plain", StandardCharsets.UTF_8);
    private static final ObjectMapper HEALTH_MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final RestClient nerClient;
    private final NerConfig config;

    private volatile Gazetteer gazetteer;

    public NerService(RestClient.Builder builder, NerConfig config) {
        this.config = config;
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(config.getReadTimeoutSeconds()));
        this.nerClient = builder
                .requestFactory(factory)
                .baseUrl(config.getPythonUrl())
                .build();
    }

    @PostConstruct
    void logAvailability() {
        if (!isHealthy()) {
            log.warn("NER service not reachable at {} — indexing will wait for it once it starts",
                    config.getPythonUrl());
        }
    }

    public boolean isHealthy() {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(config.getPythonUrl() + "/health"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                try {
                    HealthResponse health = HEALTH_MAPPER.readValue(response.body(), HealthResponse.class);
                    log.debug("NER service reachable — version: {}, model: {}", health.version(), health.model());
                } catch (Exception e) {
                    log.debug("NER service reachable (version unknown)");
                }
                return true;
            }
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // see EmbeddingService.isHealthy
            return false;
        } catch (Exception e) {
            log.debug("NER health check failed: {}", e.getMessage());
            return false;
        }
    }

    private record HealthResponse(String status, String model, String version) {}

    /**
     * The place names in {@code text}.
     *
     * @throws org.springframework.web.client.RestClientException when the service cannot be
     *         reached or fails: the caller tells an outage from a failure of this text
     */
    public GeolocationsResult extractGeolocations(String text) {
        if (text == null || text.isBlank()) {
            return GeolocationsResult.EMPTY;
        }
        NerResponse response = nerClient.post()
                .uri("/ner")
                .contentType(TEXT_PLAIN_UTF8)
                .body(text)
                .retrieve()
                .body(NerResponse.class);
        if (response == null) return GeolocationsResult.EMPTY;
        return new GeolocationsResult(
                nullSafe(response.cities()),
                nullSafe(response.departments()),
                nullSafe(response.regions()),
                nullSafe(response.countries())
        );
    }

    /**
     * The fixed country/region/department names the NER service can emit, offered as
     * choices by the search UI's geo filters. Cached after the first successful fetch:
     * the lists only change on a redeploy, which restarts this application too.
     * A failure is not cached, so the next call retries.
     */
    public Optional<Gazetteer> getGazetteer() {
        Gazetteer cached = gazetteer;
        if (cached != null) return Optional.of(cached);
        try {
            Gazetteer response = nerClient.get()
                    .uri("/gazetteer")
                    .retrieve()
                    .body(Gazetteer.class);
            if (response == null) return Optional.empty();
            Gazetteer fetched = new Gazetteer(
                    nullSafe(response.countries()),
                    nullSafe(response.regions()),
                    nullSafe(response.departments())
            );
            gazetteer = fetched;
            return Optional.of(fetched);
        } catch (Exception e) {
            log.warn("NER gazetteer unavailable at {} ({}): geo filters fall back to free text",
                    config.getPythonUrl(), e.getMessage());
            return Optional.empty();
        }
    }

    private static <T> List<T> nullSafe(List<T> list) {
        return list != null ? list : List.of();
    }

    private record NerResponse(
            List<String> cities,
            List<String> departments,
            List<String> regions,
            List<String> countries
    ) {}

    public record Gazetteer(
            List<String> countries,
            List<String> regions,
            List<Department> departments
    ) {}

    public record Department(String name, String region) {}

    public record GeolocationsResult(
            List<String> cities,
            List<String> departments,
            List<String> regions,
            List<String> countries
    ) {
        public static final GeolocationsResult EMPTY =
                new GeolocationsResult(List.of(), List.of(), List.of(), List.of());

        public boolean isEmpty() {
            return cities.isEmpty() && departments.isEmpty()
                    && regions.isEmpty() && countries.isEmpty();
        }
    }
}
