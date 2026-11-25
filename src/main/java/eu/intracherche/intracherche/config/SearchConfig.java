package eu.intracherche.intracherche.config;

import jakarta.validation.constraints.Min;

public class SearchConfig {

    @Min(1)
    private int maxPageSize = 100;

    public int getMaxPageSize() { return maxPageSize; }
    public void setMaxPageSize(int maxPageSize) { this.maxPageSize = maxPageSize; }
}

