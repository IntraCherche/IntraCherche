package eu.intracherche.intracherche.config;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "ner")
public class NerConfig {

    @NotNull
    private String pythonUrl;

    private int readTimeoutSeconds = 30;

    private int geoCapCities      = 10;
    private int geoCapDepartments = 5;
    private int geoCapRegions     = 3;
    private int geoCapCountries   = 5;

    private int workers = 1;
    private String nerModel = "fr_core_news_md";

    public String getPythonUrl() {
        return pythonUrl;
    }

    public void setPythonUrl(String pythonUrl) {
        this.pythonUrl = pythonUrl;
    }

    public int getReadTimeoutSeconds() {
        return readTimeoutSeconds;
    }

    public void setReadTimeoutSeconds(int readTimeoutSeconds) {
        this.readTimeoutSeconds = readTimeoutSeconds;
    }

    public int getGeoCapCities() { return geoCapCities; }
    public void setGeoCapCities(int geoCapCities) { this.geoCapCities = geoCapCities; }

    public int getGeoCapDepartments() { return geoCapDepartments; }
    public void setGeoCapDepartments(int geoCapDepartments) { this.geoCapDepartments = geoCapDepartments; }

    public int getGeoCapRegions() { return geoCapRegions; }
    public void setGeoCapRegions(int geoCapRegions) { this.geoCapRegions = geoCapRegions; }

    public int getGeoCapCountries() { return geoCapCountries; }
    public void setGeoCapCountries(int geoCapCountries) { this.geoCapCountries = geoCapCountries; }

    public int getWorkers() { return workers; }
    public void setWorkers(int workers) { this.workers = workers; }

    public String getNerModel() { return nerModel; }
    public void setNerModel(String nerModel) { this.nerModel = nerModel; }
}
