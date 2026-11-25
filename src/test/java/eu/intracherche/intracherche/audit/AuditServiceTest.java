package eu.intracherche.intracherche.audit;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The audit log is legal evidence, one entry per line. User-typed text must never be able to
 * break out of its quoted field or start a new line that reads as a separate, forged entry.
 */
class AuditServiceTest {

    private final Logger auditLogger = (Logger) LoggerFactory.getLogger("AUDIT");
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final AuditService service = new AuditService();
    private final MockHttpServletRequest request = new MockHttpServletRequest(); // remote addr 127.0.0.1

    @BeforeEach
    void attachAppender() {
        appender.start();
        auditLogger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        auditLogger.detachAppender(appender);
    }

    private String loggedEntry() {
        assertEquals(1, appender.list.size());
        String message = appender.list.get(0).getFormattedMessage();
        assertFalse(message.contains("\n") || message.contains("\r"), "entry spans several lines: " + message);
        return message;
    }

    @Test
    void phraseSearchQuotesAreEscaped() {
        service.logSearch(request, "alice", "\"1234 AB 56\"", List.of("Index_Archives", "Index_Demo_Wikipedia"));

        assertEquals("SEARCH | user=alice | ip=127.0.0.1 | query=\"\\\"1234 AB 56\\\"\" | indices=Index_Archives,Index_Demo_Wikipedia",
                loggedEntry());
    }

    @Test
    void lineBreakInQueryCannotForgeASecondEntry() {
        String forged = "rapport\n2026-09-11T09:00:00.000+02:00 | SEARCH | user=bob | ip=10.0.0.9 | query=\"x\" | indices=Index_3";

        service.logSearch(request, "alice", forged, List.of("Index_Archives"));

        assertEquals("SEARCH | user=alice | ip=127.0.0.1 | query=\"rapport\\n2026-09-11T09:00:00.000+02:00 | SEARCH | user=bob"
                        + " | ip=10.0.0.9 | query=\\\"x\\\" | indices=Index_3\" | indices=Index_Archives",
                loggedEntry());
    }

    /** Without escaping the backslash, the value would end in \" and the closing quote would be lost. */
    @Test
    void trailingBackslashDoesNotSwallowTheClosingQuote() {
        service.logSearch(request, "alice", "C:\\Temp\\", List.of("Index_Archives"));

        assertEquals("SEARCH | user=alice | ip=127.0.0.1 | query=\"C:\\\\Temp\\\\\" | indices=Index_Archives",
                loggedEntry());
    }

    @Test
    void sherlockQuestionControlCharactersAreEscaped() {
        service.logSherlockQuestion(request, "alice", "un\r\ndeux\u2028trois\0");

        assertEquals("SHERLOCK | user=alice | ip=127.0.0.1 | question=\"un\\r\\ndeux\\u2028trois\\u0000\"",
                loggedEntry());
    }

    @Test
    void forwardedIpAndUsernameAreEscapedToo() {
        ReflectionTestUtils.setField(service, "trustedProxy", "127.0.0.1");
        request.addHeader("X-Forwarded-For", "10.0.0.5\nforged");

        service.logSherlockQuestion(request, "alice\nbob", "question");

        assertEquals("SHERLOCK | user=alice\\nbob | ip=10.0.0.5\\nforged | question=\"question\"", loggedEntry());
    }
}
