package eu.intracherche.intracherche.alerts;

import eu.intracherche.intracherche.alerts.model.SearchSubscription;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.util.ClientUtils;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class SubscriptionQueryBuilder {

    private static final int MAX_GEO_TERMS = 10;

    /** Convenience for callers that haven't checked denyTokens availability (e.g. tests): behaves
     *  as if it were unavailable, same as before the Deny-ACE fix. */
    public static SolrQuery build(SearchSubscription sub, List<String> userSids, String solrBaseUrl) {
        return build(sub, userSids, solrBaseUrl, false);
    }

    public static SolrQuery build(SearchSubscription sub, List<String> userSids, String solrBaseUrl,
                                   boolean denyTokensAvailable) {
        SolrQuery query = new SolrQuery();
        query.setParam("df", "content");
        String q = sub.query();
        query.setQuery((q == null || q.isBlank() || "*:*".equals(q)) ? "*:*" : q);

        query.setRows(1000);
        query.setFields("parent_id");
        query.setParam("group", "false");

        List<String> indices = sub.indices();

        if (indices.size() > 1) {
            String solrHost = solrBaseUrl.replaceFirst("^https?://", "");
            String shardsValue = indices.stream()
                    .map(name -> solrHost + "/" + name)
                    .collect(Collectors.joining(","));
            query.setParam("shards", shardsValue);
            query.setParam("distrib", "true");
        }

        if (!indices.isEmpty()) {
            query.addFilterQuery("index_source:(" + String.join(" OR ", indices) + ")");
        }

        if (userSids != null && !userSids.isEmpty()) {
            String quotedSids = userSids.stream()
                    .map(token -> "\"" + token + "\"")
                    .collect(Collectors.joining(" OR "));
            query.addFilterQuery("accessTokens:(" + quotedSids + ")");
            // A Windows Deny ACE on any of the user's SIDs overrides an Allow ACE elsewhere in
            // their token — same rule as SearchController.addFilters and
            // SherlockRetriever.buildFilterQueries. Gated on denyTokensAvailable: an upgrade
            // leaves existing cores on the old schema until reindex-all runs, and filtering on
            // a field Solr doesn't have yet fails the whole query, not just the deny check.
            if (denyTokensAvailable) {
                query.addFilterQuery("-denyTokens:(" + quotedSids + ")");
            }
        } else {
            query.addFilterQuery("-*:*");
        }

        if (sub.filename() != null && !sub.filename().isBlank()) {
            String[] terms = sub.filename().toLowerCase().trim().split("\\s+");
            String wildcardPattern = Arrays.stream(terms)
                    .map(ClientUtils::escapeQueryChars)
                    .collect(Collectors.joining("*", "*", "*"));
            query.addFilterQuery("path_lc:" + wildcardPattern);
        }

        addGeoFilter(query, "geolocation_city", sub.geoCity());
        addGeoFilter(query, "geolocation_dpt", sub.geoDpt());
        addGeoFilter(query, "geolocation_region", sub.geoRegion());
        addGeoFilter(query, "geolocation_country", sub.geoCountry());

        long now = Instant.now().toEpochMilli();
        String fromMs = sub.dateOffsetFrom() != null
                ? String.valueOf(now + (long) sub.dateOffsetFrom() * 86_400_000L)
                : "*";
        String toMs = sub.dateOffsetTo() != null
                ? String.valueOf(now + (long) sub.dateOffsetTo() * 86_400_000L)
                : "*";
        if (!"*".equals(fromMs) || !"*".equals(toMs)) {
            query.addFilterQuery("mtime:[" + fromMs + " TO " + toMs + "]");
        }

        if ("mtime_desc".equals(sub.sort())) {
            query.setSort("mtime", SolrQuery.ORDER.desc);
        } else if ("mtime_asc".equals(sub.sort())) {
            query.setSort("mtime", SolrQuery.ORDER.asc);
        }

        return query;
    }

    private static void addGeoFilter(SolrQuery query, String fieldName, String values) {
        if (values == null || values.isBlank()) return;
        List<String> clauses = Arrays.stream(values.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .limit(MAX_GEO_TERMS)
                .map(s -> "\"" + ClientUtils.escapeQueryChars(s) + "\"")
                .collect(Collectors.toList());
        if (!clauses.isEmpty()) {
            query.addFilterQuery(fieldName + ":(" + String.join(" OR ", clauses) + ")");
        }
    }
}
