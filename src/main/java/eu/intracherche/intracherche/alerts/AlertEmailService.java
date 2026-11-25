package eu.intracherche.intracherche.alerts;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
public class AlertEmailService {

    private static final Logger log = LoggerFactory.getLogger(AlertEmailService.class);

    private final ObjectProvider<JavaMailSender> mailSender;
    private volatile boolean warnedNotConfigured;

    @Value("${alerts.email.from}")
    private String from;

    @Value("${alerts.email.base-url}")
    private String baseUrl;

    /** The mail sender exists only when {@code spring.mail.host} is set: without it, no email is sent. */
    public AlertEmailService(ObjectProvider<JavaMailSender> mailSender) {
        this.mailSender = mailSender;
    }

    private JavaMailSender sender() {
        JavaMailSender s = mailSender.getIfAvailable();
        if (s == null && !warnedNotConfigured) {
            warnedNotConfigured = true;
            log.info("No mail server configured (spring.mail.host): alert and answer emails are not sent; "
                    + "users still see them in the notification bell.");
        }
        return s;
    }

    public void sendAlertEmail(String toEmail, String username, String querySummary, int resultCount) {
        SimpleMailMessage msg = new SimpleMailMessage();
        msg.setFrom(from);
        msg.setTo(toEmail);
        msg.setSubject("IntraCherche : Nouveaux résultats pour \"" + querySummary + "\"");
        msg.setText(
                "Bonjour " + username + ",\n\n" +
                resultCount + " nouveaux résultats sont disponibles pour votre recherche « " + querySummary + " ».\n\n" +
                "Accédez à IntraCherche : " + baseUrl + "\n\n" +
                "Pour gérer vos alertes : " + baseUrl + "/abonnements\n\n" +
                "— IntraCherche"
        );
        JavaMailSender sender = sender();
        if (sender == null) return;
        sender.send(msg);
        log.info("Alert email sent to {} for query '{}' ({} results)", toEmail, querySummary, resultCount);
    }

    public void sendSherlockAnswerEmail(String toEmail, String username, String question, String answerExcerpt) {
        SimpleMailMessage msg = new SimpleMailMessage();
        msg.setFrom(from);
        msg.setTo(toEmail);
        msg.setSubject("IntraCherche : Sherlock a répondu à votre question");
        msg.setText(
                "Bonjour " + username + ",\n\n" +
                "Sherlock a répondu à votre question :\n" +
                "« " + question + " »\n\n" +
                answerExcerpt + "\n\n" +
                "Consultez la réponse complète sur IntraCherche : " + baseUrl + "\n\n" +
                "— IntraCherche"
        );
        JavaMailSender sender = sender();
        if (sender == null) return;
        sender.send(msg);
        log.info("Sherlock answer email sent to {} for question '{}'", toEmail, question);
    }
}
