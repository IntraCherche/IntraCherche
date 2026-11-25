package eu.intracherche.intracherche.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** Sends one message with the mail settings typed in the panel, so a wrong value shows before alerts depend on it. */
@Component
public class MailConnectionTester {

    private static final Logger log = LoggerFactory.getLogger(MailConnectionTester.class);

    /** @return empty when the message was handed to the server, else what is wrong, in words. */
    public Optional<String> send(String host, int port, String username, String password,
                                 boolean auth, boolean starttls, String from, String to) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(host);
        sender.setPort(port);
        if (username != null && !username.isBlank()) sender.setUsername(username);
        if (password != null && !password.isBlank()) sender.setPassword(password);
        var p = sender.getJavaMailProperties();
        p.put("mail.smtp.auth", String.valueOf(auth));
        p.put("mail.smtp.starttls.enable", String.valueOf(starttls));
        if (port == 465) p.put("mail.smtp.ssl.enable", "true");
        p.put("mail.smtp.connectiontimeout", "10000");
        p.put("mail.smtp.timeout", "15000");
        p.put("mail.smtp.writetimeout", "15000");
        SimpleMailMessage msg = new SimpleMailMessage();
        msg.setFrom(from);
        msg.setTo(to);
        msg.setSubject("IntraCherche : essai du courrier");
        msg.setText("Ce message confirme que IntraCherche peut envoyer des courriels avec les réglages saisis.\n\n— IntraCherche");
        try {
            sender.send(msg);
            return Optional.empty();
        } catch (MailAuthenticationException e) {
            return Optional.of("Le serveur refuse ce compte ou ce mot de passe.");
        } catch (MailSendException e) {
            log.info("Test mail failed: {}", rootMessage(e));
            return Optional.of("Envoi impossible : " + shorten(rootMessage(e)));
        } catch (RuntimeException e) {
            log.info("Test mail failed: {}", e.toString());
            return Optional.of("Envoi impossible : " + shorten(rootMessage(e)));
        }
    }

    private static String rootMessage(Throwable t) {
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        return t.getClass().getSimpleName() + (t.getMessage() == null ? "" : " - " + t.getMessage());
    }

    private static String shorten(String s) { return s.length() > 200 ? s.substring(0, 200) + "…" : s; }
}
