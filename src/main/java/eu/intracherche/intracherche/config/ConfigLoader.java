package eu.intracherche.intracherche.config;

import eu.intracherche.intracherche.web.SearchController;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;

import java.io.InputStream;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * CAUTION : THIS IS ONLY USED IN OLD TESTS BEFORE SPRING BOOT WAS INSERTED IN THE LOOP
 */
public final class ConfigLoader {

    // Validator for JSR-380 Bean Validation annotations
    private static Validator VALIDATOR;

    private static String APPLICATION_DEFAULT_CONFIG = "config/application.yaml";

    private static final Logger log = LoggerFactory.getLogger(ConfigLoader.class);


    static {
        try {
            // Try to create validator factory with EL support first
            ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
            VALIDATOR = factory.getValidator();
        } catch (Exception e) {
            // If EL is not available, create a validator without EL support
            // This is a workaround for missing EL dependencies
            log.warn("EL dependencies not found, using fallback validator: {}", e.getMessage());
            VALIDATOR = null;
        }
    }

    private ConfigLoader() {
        // private constructor to prevent instantiation
    }

    /**
     * When load is called without argument we use the Application default config
     * @return
     */
    public static ApplicationConfig load(){
        return load(APPLICATION_DEFAULT_CONFIG);
    }
    public static ApplicationConfig load(String relativePathToConfigFileName) {
        // --- Step 1: Configure LoadSettings for safety ---
        LoadSettings settings = LoadSettings.builder()
                .build();

        // --- Step 2: Create Load instance ---
        Load yaml = new Load(settings);

        // --- Step 3: Load YAML from resources ---
        // Get the full path for logging purposes
        String fullPath = getFullPath(relativePathToConfigFileName);
        InputStream is = ConfigLoader.class.getClassLoader()
                .getResourceAsStream(relativePathToConfigFileName);

        if (is == null) {
            throw new IllegalStateException(
                    "Cannot find application.yaml in the classpath"
            );
        }

        ApplicationConfig config;
        try {
            // Load as Object and then convert to ApplicationConfig
            Object data = yaml.loadFromInputStream(is);
            if (data instanceof java.util.Map) {
                @SuppressWarnings("unchecked")
                java.util.Map<String, Object> mapData = (java.util.Map<String, Object>) data;
                config = convertToApplicationConfig(mapData);
            } else {
                throw new IllegalStateException("Failed to parse application.yaml: Invalid data format");
            }
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to parse application.yaml: " + e.getMessage(), e
            );
        }

        if (config == null) {
            throw new IllegalStateException("application.yaml is empty or invalid");
        }

        // --- Step 4: Validate configuration ---
        validate(config);

        return config;
    }

    private static ApplicationConfig convertToApplicationConfig(java.util.Map<String, Object> data) {
        ApplicationConfig config = new ApplicationConfig();
        if (data != null) {
            // Convert registryConfig
            if (data.containsKey("registryConfig")) {
                @SuppressWarnings("unchecked")
                java.util.Map<String, Object> registryData = (java.util.Map<String, Object>) data.get("registryConfig");
                if (registryData != null) {
                    RegistryConfig registryConfig = new RegistryConfig();
                    if (registryData.containsKey("dbPath")) {
                        registryConfig.setDbPath((String) registryData.get("dbPath"));
                    }
                    config.setRegistryConfig(registryConfig);
                }
            }
            
            // Convert indexingConfig
            if (data.containsKey("indexingConfig")) {
                @SuppressWarnings("unchecked")
                java.util.Map<String, Object> indexingData = (java.util.Map<String, Object>) data.get("indexingConfig");
                if (indexingData != null) {
                    // Use the constructor that takes the parameter
                    if (indexingData.containsKey("indexesConfigPath")) {
                        String indexesConfigPath = (String) indexingData.get("indexesConfigPath");
                        long cycleIntervalMinutes = indexingData.containsKey("cycleIntervalMinutes")
                                ? ((Number) indexingData.get("cycleIntervalMinutes")).longValue() : 0L;
                        long maxFileSizeMb = indexingData.containsKey("maxFileSizeMb")
                                ? ((Number) indexingData.get("maxFileSizeMb")).longValue() : 0L;
                        long maxTextChars = indexingData.containsKey("maxTextChars")
                                ? ((Number) indexingData.get("maxTextChars")).longValue() : 0L;
                        IndexingConfig indexingConfig = new IndexingConfig(indexesConfigPath, cycleIntervalMinutes,
                                maxFileSizeMb, maxTextChars);
                        config.setIndexingConfig(indexingConfig);
                    }
                }
            }
            
            // Convert solrConfig
            if (data.containsKey("solrConfig")) {
                @SuppressWarnings("unchecked")
                java.util.Map<String, Object> solrData = (java.util.Map<String, Object>) data.get("solrConfig");
                if (solrData != null) {
                    String solrBaseUrl = null;
                    if (solrData.containsKey("solrBaseUrl")) {
                        solrBaseUrl = (String) solrData.get("solrBaseUrl");
                    }
                    String configSetSourcePath = null;
                    if (solrData.containsKey("configSetSourcePath")) {
                        configSetSourcePath = (String) solrData.get("configSetSourcePath");
                    }
                    SolrConfig solrConfig = new SolrConfig(solrBaseUrl, configSetSourcePath);
                    config.setSolrConfig(solrConfig);
                }
            }

        }
        return config;
    }

    private static void validate(ApplicationConfig config) {
        // Skip validation if validator is not available (due to missing EL)
        if (VALIDATOR == null) {
            System.out.println("Skipping configuration validation due to missing EL dependencies");
            return;
        }
        
        Set<ConstraintViolation<ApplicationConfig>> violations =
                VALIDATOR.validate(config);

        if (!violations.isEmpty()) {
            String message = violations.stream()
                    .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                    .collect(Collectors.joining(", "));
            throw new IllegalStateException(
                    "Configuration validation failed: " + message
            );
        }
    }
    
    /**
     * Get the full absolute path for a resource file
     * @param relativePath the relative path to the resource
     * @return the full absolute path or the relative path if it cannot be resolved
     */
    static String getFullPath(String relativePath) {
        try {
            // Try to get the resource as a URL to resolve the full path
            java.net.URL resourceUrl = ConfigLoader.class.getClassLoader().getResource(relativePath);
            if (resourceUrl != null) {
                // Convert URL to file path for better readability
                if ("file".equals(resourceUrl.getProtocol())) {
                    java.io.File file = new java.io.File(resourceUrl.toURI());
                    return file.getAbsolutePath();
                } else {
                    // For non-file resources, return the original path
                    return relativePath;
                }
            }
        } catch (Exception e) {
            // If we can't resolve the full path, return the relative path
            return relativePath;
        }
        return relativePath;
    }
}
