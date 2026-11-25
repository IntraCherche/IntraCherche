package eu.intracherche.intracherche.config;

import jakarta.validation.constraints.NotNull;

import java.nio.file.Path;
import java.nio.file.Paths;

public class RegistryConfig {

    @NotNull
    private String dbPath;
    
    private int stalledFilesThresholdHours = 720; // Default to 30 days (30 * 24 hours)

    private int safetyValveThresholdPercent = 30;

    public String getDbPath() { return dbPath; }
    public void setDbPath(String dbPath) { this.dbPath = dbPath; }

    public int getStalledFilesThresholdHours() {
        return stalledFilesThresholdHours;
    }

    public void setStalledFilesThresholdHours(int stalledFilesThresholdHours) {
        this.stalledFilesThresholdHours = stalledFilesThresholdHours;
    }

    public int getSafetyValveThresholdPercent() {
        return safetyValveThresholdPercent;
    }

    public void setSafetyValveThresholdPercent(int safetyValveThresholdPercent) {
        this.safetyValveThresholdPercent = safetyValveThresholdPercent;
    }
}

