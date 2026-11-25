package eu.intracherche.intracherche.web;

import eu.intracherche.intracherche.config.IndexConfig;
import eu.intracherche.intracherche.config.IndexesConfig;
import com.github.benmanes.caffeine.cache.Ticker;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrRequest;
import org.apache.solr.client.solrj.request.CoreAdminRequest;
import org.apache.solr.client.solrj.request.QueryRequest;
import org.apache.solr.common.util.NamedList;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** What the search bar asks while typing: {@code /api/spell} and {@code /api/complete}. */
class SearchControllerSpellTest {

    private static final String CORE = "Index_Demo_OCR";

    @Test
    void every_solr_request_carries_the_users_access_filter() throws Exception {
        SolrClient solr = mock(SolrClient.class);
        when(solr.request(any(SolrRequest.class), nullable(String.class))).thenAnswer(call -> {
            if (call.getArgument(0) instanceof CoreAdminRequest) {
                NamedList<Object> status = new NamedList<>();
                status.add(CORE, new NamedList<>());
                NamedList<Object> response = new NamedList<>();
                response.add("status", status);
                return response;
            }
            // The count request: the word is on no readable page, so it goes on to the spellchecker.
            NamedList<Object> facetQueries = new NamedList<>();
            facetQueries.add("{!lucene df=content q.op=AND v=$sw0}", 0);
            NamedList<Object> facetCounts = new NamedList<>();
            facetCounts.add("facet_queries", facetQueries);
            NamedList<Object> response = new NamedList<>();
            response.add("facet_counts", facetCounts);
            return response;
        });
        SearchAccessControl access = mock(SearchAccessControl.class);
        when(access.getCurrentUserAuthorities()).thenReturn(List.of("S-1-5-21-1-2-3-1105"));
        SearchController controller = controller(solr);
        ReflectionTestUtils.setField(controller, "searchAccessControl", access);

        mvc(controller).perform(get("/api/spell").param("q", "fauvilet").param("indices", CORE))
                .andExpect(status().isOk());

        ArgumentCaptor<SolrRequest<?>> requests = ArgumentCaptor.forClass(SolrRequest.class);
        verify(solr, atLeast(3)).request(requests.capture(), nullable(String.class));
        List<QueryRequest> queries = requests.getAllValues().stream()
                .filter(QueryRequest.class::isInstance).map(QueryRequest.class::cast).toList();
        assertThat(queries).hasSize(2); // the count, then the spellcheck of "fauvilet"
        for (QueryRequest query : queries) {
            assertThat(Arrays.asList(query.getParams().getParams("fq")))
                    .contains("accessTokens:(\"S-1-5-21-1-2-3-1105\")");
        }
    }

    @Test
    void completion_counts_its_candidates_under_the_users_access_filter() throws Exception {
        SolrClient solr = mock(SolrClient.class);
        when(solr.request(any(SolrRequest.class), nullable(String.class))).thenAnswer(call -> {
            if (call.getArgument(0) instanceof CoreAdminRequest) {
                NamedList<Object> status = new NamedList<>();
                status.add(CORE, new NamedList<>());
                NamedList<Object> response = new NamedList<>();
                response.add("status", status);
                return response;
            }
            NamedList<Object> response = new NamedList<>();
            if ("/terms".equals(((QueryRequest) call.getArgument(0)).getParams().get("qt"))) {
                NamedList<Object> words = new NamedList<>();
                words.add("fauvillet", 4);
                NamedList<Object> terms = new NamedList<>();
                terms.add("content_exact", words);
                response.add("terms", terms);
            } else {
                NamedList<Object> facetQueries = new NamedList<>();
                facetQueries.add("{!term f=content_exact v=$tw0}", 1);
                NamedList<Object> facetCounts = new NamedList<>();
                facetCounts.add("facet_queries", facetQueries);
                response.add("facet_counts", facetCounts);
            }
            return response;
        });
        SearchAccessControl access = mock(SearchAccessControl.class);
        when(access.getCurrentUserAuthorities()).thenReturn(List.of("S-1-5-21-1-2-3-1105"));
        SearchController controller = controller(solr);
        ReflectionTestUtils.setField(controller, "searchAccessControl", access);

        mvc(controller).perform(get("/api/complete").param("q", "affaire Fauvil").param("indices", CORE))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"completions\":[\"affaire Fauvillet\"]}"));

        ArgumentCaptor<SolrRequest<?>> requests = ArgumentCaptor.forClass(SolrRequest.class);
        verify(solr, atLeast(3)).request(requests.capture(), nullable(String.class));
        List<QueryRequest> counts = requests.getAllValues().stream()
                .filter(QueryRequest.class::isInstance).map(QueryRequest.class::cast)
                .filter(query -> !"/terms".equals(query.getParams().get("qt")))
                .toList();
        assertThat(counts).hasSize(1);
        assertThat(Arrays.asList(counts.getFirst().getParams().getParams("fq")))
                .contains("accessTokens:(\"S-1-5-21-1-2-3-1105\")");
    }

    @Test
    void over_its_limit_answers_nothing_without_querying_solr_or_using_up_searches() throws Exception {
        SolrClient solr = mock(SolrClient.class);
        SearchController controller = controller(solr);
        SearchRateLimiter spellLimiter = new SearchRateLimiter(1, Ticker.systemTicker());
        spellLimiter.tryAcquire("127.0.0.1"); // allowance already used (no login here: keyed by IP)
        ReflectionTestUtils.setField(controller, "typingRateLimiter", spellLimiter);
        SearchRateLimiter searchLimiter = new SearchRateLimiter(1, Ticker.systemTicker());
        ReflectionTestUtils.setField(controller, "searchRateLimiter", searchLimiter);

        mvc(controller).perform(get("/api/spell").param("q", "fauvilet").param("indices", CORE))
                .andExpect(status().isOk())
                .andExpect(content().json("{}"));

        verifyNoInteractions(solr);
        assertThat(searchLimiter.tryAcquire("127.0.0.1")).as("the one search allowed is still there").isTrue();
    }

    private static SearchController controller(SolrClient solr) {
        SearchController controller = new SearchController(solr);
        ReflectionTestUtils.setField(controller, "solrConfig", mock(eu.intracherche.intracherche.config.SolrConfig.class));
        IndexConfig index = new IndexConfig();
        index.setCoreName(CORE);
        index.setVisibleToAll(true);
        IndexesConfig indexes = new IndexesConfig();
        indexes.setIndexes(List.of(index));
        ReflectionTestUtils.setField(controller, "indexesConfig", indexes);
        return controller;
    }

    private static MockMvc mvc(SearchController controller) {
        return MockMvcBuilders.standaloneSetup(controller).build();
    }
}
