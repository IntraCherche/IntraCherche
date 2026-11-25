package eu.intracherche.intracherche.sherlock;

import eu.intracherche.intracherche.audit.AuditService;
import eu.intracherche.intracherche.config.LlmConfig;
import eu.intracherche.intracherche.config.RerankerConfig;
import eu.intracherche.intracherche.security.SecurityService;
import eu.intracherche.intracherche.services.RerankerService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * llm.enabled: false, as INSTALL.md describes it: "Le panneau Sherlock disparaît de l'interface"
 * and Sherlock is off as a whole — not merely its calls to Ollama. It once only silenced Ollama:
 * the pane still showed, and questions were queued, retrieved and answered with
 * "(Service LLM désactivé)". What is pinned here is everything Sherlock owns going quiet.
 */
class SherlockDisabledTest {

    private static final String SHERLOCK_SID = "S-1-5-21-111111111-222222222-333333333-4321";

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void a_question_is_refused_with_the_disabled_message_even_for_a_group_member() throws Exception {
        LlmConfig llm = new LlmConfig();
        llm.setEnabled(false);
        llm.setSherlockGroupSid(SHERLOCK_SID);
        SherlockService sherlockService = mock(SherlockService.class);
        LlmService llmService = mock(LlmService.class);
        SherlockController controller = controller(llm, sherlockService, llmService);
        signIn("alice", SHERLOCK_SID);

        ResponseEntity<?> response = controller.ask(mock(HttpServletRequest.class),
                new SherlockController.AskRequest("Qui a signé le rapport ?", null, null, null, null));

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(((Map<?, ?>) response.getBody()).get("error").toString())
                .contains("Sherlock est désactivé sur ce serveur");
        verify(sherlockService, never()).ask(anyString(), anyString(), anyList(), anyString(), any(), any(), any(), any());
        verifyNoInteractions(llmService);
    }

    @Test
    void enabled_sherlock_still_accepts_a_group_members_question() throws Exception {
        LlmConfig llm = new LlmConfig();
        llm.setSherlockGroupSid(SHERLOCK_SID);
        SherlockService sherlockService = mock(SherlockService.class);
        LlmService llmService = mock(LlmService.class);
        when(llmService.isHealthy()).thenReturn(true);
        when(sherlockService.ask(anyString(), anyString(), anyList(), anyString(), any(), any(), any(), any()))
                .thenReturn(new SherlockQuestion("q1", "alice", "", "Qui a signé le rapport ?",
                        SherlockQuestionStatus.QUEUED, List.of(SHERLOCK_SID), null, List.of(),
                        System.currentTimeMillis(), null, null, List.of(), List.of(), null, null, java.util.Map.of(), null));
        SherlockController controller = controller(llm, sherlockService, llmService);
        signIn("alice", SHERLOCK_SID);

        ResponseEntity<?> response = controller.ask(mock(HttpServletRequest.class),
                new SherlockController.AskRequest("Qui a signé le rapport ?", null, null, null, null));

        assertThat(response.getStatusCode().value()).isEqualTo(202);
    }

    /** The reranker only ever serves Sherlock: off with it, whatever reranker.enabled says. */
    @Test
    void the_reranker_is_off_when_sherlock_is() {
        LlmConfig llm = new LlmConfig();
        llm.setEnabled(false);
        RerankerConfig reranker = new RerankerConfig();
        reranker.useLlm(llm);

        assertThat(reranker.isEnabled()).as("reranker.enabled itself is untouched").isTrue();
        assertThat(reranker.isUsed()).isFalse();
        assertThat(reranker.startsHere()).as("no reranker process is started").isFalse();
        assertThat(new RerankerService(RestClient.builder(), reranker).isEnabled()).isFalse();
    }

    @Test
    void the_reranker_runs_here_while_sherlock_is_on() {
        RerankerConfig reranker = new RerankerConfig();
        reranker.useLlm(new LlmConfig());

        assertThat(reranker.isUsed()).isTrue();
        assertThat(reranker.startsHere()).isTrue();
    }

    /** reranker.enabled: false — "skip reranking": nothing to start either. */
    @Test
    void a_disabled_reranker_is_not_started() {
        RerankerConfig reranker = new RerankerConfig();
        reranker.useLlm(new LlmConfig());
        reranker.setEnabled(false);

        assertThat(reranker.startsHere()).isFalse();
        // Sherlock then reads the maxSnippets best passages by retrieval order.
        LlmConfig llm = new LlmConfig();
        SherlockRetriever.Settings settings = SherlockRetriever.Settings.from(llm, reranker, reranker.isUsed(), false);
        assertThat(settings.candidates()).isEqualTo(llm.getMaxSnippets());
        assertThat(settings.maxSnippets()).isEqualTo(llm.getMaxSnippets());
    }

    // ── Fixtures ─────────────────────────────────────────────────────────────

    private static SherlockController controller(LlmConfig llm, SherlockService sherlockService, LlmService llmService) {
        SecurityService securityService = mock(SecurityService.class);
        when(securityService.getEmailForUser(anyString())).thenReturn(java.util.Optional.empty());
        SherlockQuota quota = mock(SherlockQuota.class);
        try {
            when(quota.consume(anyString(), anyList())).thenReturn(new SherlockQuota.Consumption(true,
                    new SherlockQuota.Usage(LlmConfig.Tier.BASE, 1, 0)));
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
        return new SherlockController(sherlockService, securityService, mock(AuditService.class),
                llm, llmService, quota, mock(SherlockQuestionDao.class));
    }

    private static void signIn(String username, String... sids) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                username, null, List.of(sids).stream().map(SimpleGrantedAuthority::new).toList()));
    }
}
