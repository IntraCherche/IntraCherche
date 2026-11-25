package eu.intracherche.intracherche;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.ldap.autoconfigure.LdapAutoConfiguration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

// We exclude LdapAutoConfig since it is manually configured
@SpringBootApplication(exclude = { LdapAutoConfiguration.class })
@EnableAsync
@EnableScheduling
public class IntraChercheMainApplication {

    private static final Logger log = LoggerFactory.getLogger(IntraChercheMainApplication.class);

    @Value("${app.version}")
    private String appVersion;

    public static void main(String[] args) {
        SpringApplication.run(IntraChercheMainApplication.class, args);
    }

    @PostConstruct
    public void logVersion() {
        log.info("=================================================");
        log.info("  IntraCherche v{} — démarré avec succès", appVersion);
        log.info("=================================================");
    }
}
