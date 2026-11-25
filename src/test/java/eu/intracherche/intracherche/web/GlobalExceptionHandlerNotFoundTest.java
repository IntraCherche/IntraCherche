package eu.intracherche.intracherche.web;

import eu.intracherche.intracherche.audit.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A path that matches nothing is a 404, not a server error: Spring raises NoResourceFoundException for it,
 * and the catch-all handler used to turn that into a 500 and an ERROR line with a stack trace.
 */
class GlobalExceptionHandlerNotFoundTest {

    @RestController
    static class Probe {
        @GetMapping("/api/missing")
        String missing() throws NoResourceFoundException {
            throw new NoResourceFoundException(HttpMethod.GET, "/api/missing", "api/missing");
        }

        @GetMapping("/api/broken")
        String broken() {
            throw new IllegalStateException("boom");
        }
    }

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new Probe())
                .setControllerAdvice(new GlobalExceptionHandler(Mockito.mock(AuditService.class)))
                .build();
    }

    @Test
    void a_path_that_matches_nothing_is_a_404() throws Exception {
        mvc.perform(get("/api/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"));
    }

    @Test
    void a_real_failure_is_still_a_500() throws Exception {
        mvc.perform(get("/api/broken"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500));
    }
}
