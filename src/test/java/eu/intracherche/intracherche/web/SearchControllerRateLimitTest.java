package eu.intracherche.intracherche.web;

import eu.intracherche.intracherche.audit.AuditService;
import com.github.benmanes.caffeine.cache.Ticker;
import org.apache.solr.client.solrj.SolrClient;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** A search over the user's allowance is refused with 429 before it costs Solr anything. */
class SearchControllerRateLimitTest {

    @Test
    void search_over_the_limit_is_refused_with_429_without_querying_solr() throws Exception {
        SolrClient solr = mock(SolrClient.class);
        SearchController controller = new SearchController(solr);
        SearchRateLimiter limiter = new SearchRateLimiter(1, Ticker.systemTicker());
        limiter.tryAcquire("127.0.0.1"); // allowance already used (no login here: keyed by IP)
        ReflectionTestUtils.setField(controller, "searchRateLimiter", limiter);
        ReflectionTestUtils.setField(controller, "auditService", mock(AuditService.class));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(get("/api/search").param("q", "remplacement").param("indices", "Index_Demo_OCR"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "60"))
                .andExpect(jsonPath("$.error").exists());

        verifyNoInteractions(solr);
    }
}
