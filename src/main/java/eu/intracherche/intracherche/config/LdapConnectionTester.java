package eu.intracherche.intracherche.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.ldap.core.support.LdapContextSource;
import org.springframework.stereotype.Component;

import java.util.Optional;

import static org.springframework.ldap.query.LdapQueryBuilder.query;

/**
 * Tries the directory settings before they are saved: a wrong value here stops everyone from
 * logging in, the administrator included, so a candidate that does not bind is refused.
 */
@Component
public class LdapConnectionTester {

    private static final Logger log = LoggerFactory.getLogger(LdapConnectionTester.class);

    /** @return empty when the account can bind and the user folder exists, else what is wrong, in words. */
    public Optional<String> test(String urls, String base, String username, String password, String userSearchBase) {
        LdapContextSource source = new LdapContextSource();
        try {
            source.setUrl(urls);
            source.setBase(base);
            source.setUserDn(username);
            source.setPassword(password);
            source.setPooled(false);
            source.afterPropertiesSet();
            LdapTemplate template = new LdapTemplate(source);
            template.setIgnorePartialResultException(true);
            template.setIgnoreNameNotFoundException(false);
            String folder = userSearchBase == null ? "" : userSearchBase;
            template.search(query().base(folder).countLimit(1).where("objectClass").isPresent(),
                    (javax.naming.directory.Attributes a) -> a);
            return Optional.empty();
        } catch (org.springframework.ldap.AuthenticationException e) {
            return Optional.of("L'annuaire refuse ce compte ou ce mot de passe.");
        } catch (org.springframework.ldap.NameNotFoundException e) {
            return Optional.of("Le dossier « " + userSearchBase + " » n'existe pas dans l'annuaire (relativement à la racine).");
        } catch (RuntimeException e) {
            Throwable root = e;
            while (root.getCause() != null) root = root.getCause();
            log.debug("LDAP test failed", e);
            String why = root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
            return Optional.of("Connexion impossible : " + why);
        }
    }
}
