package eu.intracherche.intracherche.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MailConnectionTesterTest {
    @Test
    void anUnreachableServerIsReportedInWords() {
        var why = new MailConnectionTester().send("127.0.0.1", 1, "", "", false, false, "a@example.org", "b@example.org");
        assertTrue(why.isPresent());
        assertTrue(why.get().startsWith("Envoi impossible"), why.get());
    }
}
