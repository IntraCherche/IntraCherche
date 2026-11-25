package eu.intracherche.intracherche.config;

import eu.intracherche.intracherche.discovery.SmbClientManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SMBConfig {

    @Bean
    public SmbClientManager smbClientManager() {
        // This is initialized exactly once at startup
        return new SmbClientManager();
    }
}
