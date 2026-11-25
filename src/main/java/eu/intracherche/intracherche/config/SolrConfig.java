package eu.intracherche.intracherche.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "solr-config")
public record SolrConfig(
    String solrBaseUrl,
    // Where to read the configset files from, relative to the working directory.
    // Dev default matches the source tree; production installer sets this to config/solr/configset.
    String configSetSourcePath
) {
}
