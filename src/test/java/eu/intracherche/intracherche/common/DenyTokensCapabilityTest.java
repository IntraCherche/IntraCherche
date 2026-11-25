package eu.intracherche.intracherche.common;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrRequest;
import org.apache.solr.common.util.NamedList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// Regression tests for the deploy-breaking bug the deny-ACE fix introduced: adding
// "-denyTokens:(...)" to every query unconditionally makes Solr reject the WHOLE query with
// "undefined field denyTokens" on any core that hasn't had the new schema pushed yet
// (reindex-all). isAvailable() must gate that, and self-heal once the field does exist.
class DenyTokensCapabilityTest {

    @AfterEach
    void reset() {
        DenyTokensCapability.resetForTests();
    }

    @Test
    void unavailable_when_the_field_does_not_exist_yet() throws Exception {
        SolrClient solrClient = mock(SolrClient.class);
        when(solrClient.request(any(SolrRequest.class), anyString()))
                .thenThrow(new org.apache.solr.common.SolrException(
                        org.apache.solr.common.SolrException.ErrorCode.BAD_REQUEST, "undefined field denyTokens"));

        assertThat(DenyTokensCapability.isAvailable(solrClient, "Index_Demo")).isFalse();
    }

    @Test
    void available_once_the_field_exists() throws Exception {
        // Shaped like Solr's real GET /schema/fields/denyTokens body: a "field" entry is what
        // SchemaResponse.FieldResponse.setResponse() reads; an empty NamedList (no such key)
        // throws while parsing, which is indistinguishable here from "field not found" — this is
        // exactly why the fix must gate on that same real response shape, not just "no exception".
        NamedList<Object> field = new NamedList<>();
        field.add("name", "denyTokens");
        field.add("type", "strings");
        NamedList<Object> response = new NamedList<>();
        response.add("field", field);

        SolrClient solrClient = mock(SolrClient.class);
        when(solrClient.request(any(SolrRequest.class), anyString())).thenReturn(response);

        assertThat(DenyTokensCapability.isAvailable(solrClient, "Index_Demo")).isTrue();
    }

    @Test
    void unknown_state_is_treated_as_unavailable_not_as_a_thrown_error() {
        // No core name yet (e.g. an empty index list): must not attempt a call or throw.
        assertThat(DenyTokensCapability.isAvailable(mock(SolrClient.class), null)).isFalse();
        assertThat(DenyTokensCapability.isAvailable(mock(SolrClient.class), "")).isFalse();
    }
}
