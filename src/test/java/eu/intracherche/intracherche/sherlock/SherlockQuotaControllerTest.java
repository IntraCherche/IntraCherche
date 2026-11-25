package eu.intracherche.intracherche.sherlock;

import eu.intracherche.intracherche.audit.AuditService;
import eu.intracherche.intracherche.config.LlmConfig;
import eu.intracherche.intracherche.registry.RegistrySchema;
import eu.intracherche.intracherche.security.SecurityService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Every question goes through POST /questions: the quota is checked there, before anything is queued. */
class SherlockQuotaControllerTest {

    private static final SherlockController.AskRequest QUESTION =
            new SherlockController.AskRequest("Qui a signé le rapport ?", null, null, null, null);

    private LlmConfig llm;
    private SherlockQuota quota;
    private SherlockService sherlockService;
    private SherlockQuestionDao dao;
    private AuditService audit;
    private SherlockController controller;

    @BeforeEach
    void setUp() throws Exception {
        Connection conn = DriverManager.getConnection("jdbc:sqlite::memory:");
        conn.setAutoCommit(false);
        RegistrySchema.ensureSchema(conn);
        llm = new LlmConfig();
        llm.getQuota().setBase(2);
        quota = new SherlockQuota(conn, llm);
        sherlockService = mock(SherlockService.class);
        when(sherlockService.ask(anyString(), anyString(), anyList(), anyString(), any(), any(), any(), any()))
                .thenReturn(new SherlockQuestion("q1", "alice", "", "Qui a signé le rapport ?",
                        SherlockQuestionStatus.QUEUED, List.of(), null, List.of(),
                        System.currentTimeMillis(), null, null, List.of(), List.of(), null, null, Map.of(), null));
        dao = mock(SherlockQuestionDao.class);
        audit = mock(AuditService.class);
        LlmService llmService = mock(LlmService.class);
        when(llmService.isHealthy()).thenReturn(true);
        SecurityService securityService = mock(SecurityService.class);
        when(securityService.getEmailForUser(anyString())).thenReturn(java.util.Optional.empty());
        controller = new SherlockController(sherlockService, securityService, audit, llm, llmService,
                quota, dao);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "alice", null, List.of(new SimpleGrantedAuthority("S-1-5-21-1-1001"))));
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    private ResponseEntity<?> ask() throws Exception {
        return controller.ask(mock(HttpServletRequest.class), QUESTION);
    }

    @Test
    void the_question_past_the_quota_is_refused_before_being_queued() throws Exception {
        assertThat(ask().getStatusCode().value()).isEqualTo(202);
        assertThat(ask().getStatusCode().value()).isEqualTo(202);

        ResponseEntity<?> refused = ask();
        assertThat(refused.getStatusCode().value()).isEqualTo(429);
        assertThat(((Map<?, ?>) refused.getBody()).get("error").toString())
                .contains("vos 2 questions du jour").contains("minuit");
        verify(sherlockService, times(2)).ask(anyString(), anyString(), anyList(), anyString(), any(), any(), any(), any());
        verify(audit).logSecurityEvent(any(), eq("alice"), eq("SHERLOCK_QUOTA"), anyString());
    }

    @Test
    void the_pane_reads_the_days_count() throws Exception {
        ask();
        assertThat(controller.quota()).containsEntry("used", 1).containsEntry("limit", 2).containsEntry("tier", "BASE");

        llm.getQuota().setBase(0);
        assertThat(controller.quota()).as("unlimited").containsEntry("limit", null);
    }

    @Test
    void a_question_refused_by_a_full_queue_is_not_counted() throws Exception {
        when(sherlockService.ask(anyString(), anyString(), anyList(), anyString(), any(), any(), any(), any()))
                .thenThrow(new SherlockQueueFullException());

        try {
            ask();
        } catch (SherlockQueueFullException expected) {
            // answered 503 by the controller's exception handler
        }
        assertThat(controller.quota()).containsEntry("used", 0);
    }

    @Test
    void a_user_with_questions_waiting_is_asked_to_wait_first() throws Exception {
        when(dao.countPending("alice")).thenReturn(2);

        ResponseEntity<?> refused = ask();
        assertThat(refused.getStatusCode().value()).isEqualTo(429);
        assertThat(((Map<?, ?>) refused.getBody()).get("error").toString()).contains("en attente");
        assertThat(controller.quota()).as("not counted").containsEntry("used", 0);

        llm.getQuota().setMaxPending(0);
        assertThat(ask().getStatusCode().value()).as("0 = no limit").isEqualTo(202);
    }
}
