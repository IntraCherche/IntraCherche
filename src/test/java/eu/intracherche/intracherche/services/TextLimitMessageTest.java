package eu.intracherche.intracherche.services;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TextLimitMessageTest {

    @Test
    void saysWhatWasKeptAndWhichSettingToRaise() {
        String message = FileIndexingService.textLimitMessage(50_000_000L, 8421);

        assertTrue(message.contains("50000000 premiers caractères"), message);
        assertTrue(message.contains("8421 sections"), message);
        assertTrue(message.contains("indexing-config.maxTextChars"), message);
        assertTrue(message.contains("/etc/intracherche/application.yaml"), message);
    }
}
