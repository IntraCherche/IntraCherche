package eu.intracherche.intracherche.web;

import eu.intracherche.intracherche.config.IndexConfig;
import eu.intracherche.intracherche.config.IndexesConfig;
import eu.intracherche.intracherche.config.SolrConfig;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * An index restricted by visibleToGroups is neither listed nor searched for a user outside its
 * groups — not by naming it in the request, not by leaving the selection empty.
 */
class SearchControllerIndexVisibilityTest {

    private static final String MEDICAL = "S-1-5-21-9-1120";
    private static final String HR_USER = "S-1-5-21-9-1106";

    private final SolrClient solr = mock(SolrClient.class);

    private SearchController controllerFor(String userSid) throws Exception {
        when(solr.request(any(SolrRequest.class), nullable(String.class))).thenAnswer(call -> {
            NamedList<Object> response = new NamedList<>();
            if (call.getArgument(0) instanceof CoreAdminRequest) {
                NamedList<Object> cores = new NamedList<>();
                cores.add("EMC-Saurat", new NamedList<>());
                cores.add("Tous", new NamedList<>());
                response.add("status", cores);
            }
            return response;
        });
        IndexConfig saurat = new IndexConfig();
        saurat.setCoreName("EMC-Saurat");
        saurat.setVisibleToGroups(List.of("Medical"));
        saurat.setVisibleToGroupSids(Map.of("Medical", MEDICAL));
        IndexConfig tous = new IndexConfig();
        tous.setCoreName("Tous");
        tous.setVisibleToAll(true);
        IndexesConfig indexes = new IndexesConfig();
        indexes.setIndexes(List.of(saurat, tous));

        SearchAccessControl access = mock(SearchAccessControl.class);
        when(access.getCurrentUserAuthorities()).thenReturn(List.of(userSid, "S-1-1-0"));
        SearchController controller = new SearchController(solr);
        ReflectionTestUtils.setField(controller, "solrConfig", mock(SolrConfig.class));
        ReflectionTestUtils.setField(controller, "indexesConfig", indexes);
        ReflectionTestUtils.setField(controller, "searchAccessControl", access);
        return controller;
    }

    private static MockMvc mvc(SearchController controller) {
        return MockMvcBuilders.standaloneSetup(controller).build();
    }

    private List<String> indexSourceFilters() throws Exception {
        ArgumentCaptor<SolrRequest<?>> requests = ArgumentCaptor.forClass(SolrRequest.class);
        verify(solr, atLeastOnce()).request(requests.capture(), nullable(String.class));
        return requests.getAllValues().stream()
                .filter(QueryRequest.class::isInstance).map(QueryRequest.class::cast)
                .flatMap(q -> Arrays.stream(q.getParams().getParams("fq")))
                .filter(fq -> fq.startsWith("index_source:"))
                .toList();
    }

    @Test
    void the_sources_column_lists_only_what_the_user_may_see() throws Exception {
        mvc(controllerFor(HR_USER)).perform(get("/api/indices"))
                .andExpect(status().isOk()).andExpect(content().json("[\"Tous\"]", true));
        mvc(controllerFor(MEDICAL)).perform(get("/api/indices"))
                .andExpect(content().json("[\"EMC-Saurat\",\"Tous\"]", true));
    }

    @Test
    void naming_a_hidden_index_in_the_request_reaches_nothing() throws Exception {
        mvc(controllerFor(HR_USER)).perform(get("/api/spell").param("q", "fauvilet").param("indices", "EMC-Saurat"))
                .andExpect(status().isOk()).andExpect(content().json("{}"));
        assertThat(indexSourceFilters()).isEmpty();
    }

    @Test
    void an_empty_selection_means_the_visible_indexes_only() throws Exception {
        mvc(controllerFor(HR_USER)).perform(get("/api/spell").param("q", "fauvilet"))
                .andExpect(status().isOk());
        assertThat(indexSourceFilters()).isNotEmpty().allMatch("index_source:(Tous)"::equals);
    }
}
