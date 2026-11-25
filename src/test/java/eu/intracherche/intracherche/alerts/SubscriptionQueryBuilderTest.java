package eu.intracherche.intracherche.alerts;

import eu.intracherche.intracherche.alerts.model.NotificationFrequency;
import eu.intracherche.intracherche.alerts.model.SearchSubscription;
import org.apache.solr.client.solrj.SolrQuery;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SubscriptionQueryBuilderTest {

    private static final String SOLR_BASE = "http://localhost:8983/solr";
    private static final List<String> SIDS = List.of("S-1-1-0");

    private SearchSubscription sub(String query) {
        return new SearchSubscription(
                "s1", "alice", "alice@corp.local",
                query, List.of("idx-docs"),
                null, null, null, null, null,
                null, null, null, NotificationFrequency.IMMEDIATE,
                null, null, null, System.currentTimeMillis(), true
        );
    }

    @Test
    void multi_word_query_is_passed_raw_without_escaping() {
        SolrQuery q = SubscriptionQueryBuilder.build(sub("climate change"), SIDS, SOLR_BASE);
        // spaces must NOT be escaped — "climate\ change" would be a phrase query, not two terms
        assertThat(q.getQuery()).isEqualTo("climate change");
    }

    @Test
    void special_solr_chars_are_passed_raw_so_users_can_use_boolean_syntax() {
        SolrQuery q = SubscriptionQueryBuilder.build(sub("loi AND peche"), SIDS, SOLR_BASE);
        assertThat(q.getQuery()).isEqualTo("loi AND peche");
    }

    @Test
    void null_query_maps_to_match_all() {
        SolrQuery q = SubscriptionQueryBuilder.build(sub(null), SIDS, SOLR_BASE);
        assertThat(q.getQuery()).isEqualTo("*:*");
    }

    @Test
    void blank_query_maps_to_match_all() {
        SolrQuery q = SubscriptionQueryBuilder.build(sub("  "), SIDS, SOLR_BASE);
        assertThat(q.getQuery()).isEqualTo("*:*");
    }

    @Test
    void empty_sids_adds_deny_all_filter() {
        SolrQuery q = SubscriptionQueryBuilder.build(sub("test"), List.of(), SOLR_BASE);
        assertThat(Arrays.asList(q.getFilterQueries())).contains("-*:*");
    }

    @Test
    void non_empty_sids_adds_access_token_filter() {
        SolrQuery q = SubscriptionQueryBuilder.build(sub("test"), List.of("S-1-1-0", "S-1-5-11"), SOLR_BASE);
        assertThat(Arrays.asList(q.getFilterQueries()))
                .anyMatch(fq -> fq.startsWith("accessTokens:(") && fq.contains("S-1-1-0") && fq.contains("S-1-5-11"));
    }
}
