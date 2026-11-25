package eu.intracherche.intracherche.alerts;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.mail.autoconfigure.MailSenderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.*;

/** The administrator may not want email at all: without spring.mail.host the application must still start. */
class AlertEmailServiceWithoutMailTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MailSenderAutoConfiguration.class))
            .withBean(AlertEmailService.class)
            .withPropertyValues("alerts.email.from=a@example.org", "alerts.email.base-url=http://x");

    @Test
    void startsAndSilentlySkipsEmailsWhenNoHostIsSet() {
        runner.run(ctx -> {
            assertNull(ctx.getBeanProvider(org.springframework.mail.javamail.JavaMailSender.class).getIfAvailable());
            var svc = ctx.getBean(AlertEmailService.class);
            assertDoesNotThrow(() -> svc.sendAlertEmail("u@example.org", "u", "q", 3));
            assertDoesNotThrow(() -> svc.sendSherlockAnswerEmail("u@example.org", "u", "q", "a"));
        });
    }

    @Test
    void sendsWhenAHostIsSet() {
        runner.withPropertyValues("spring.mail.host=127.0.0.1").run(ctx ->
                assertNotNull(ctx.getBeanProvider(org.springframework.mail.javamail.JavaMailSender.class).getIfAvailable()));
    }
}
