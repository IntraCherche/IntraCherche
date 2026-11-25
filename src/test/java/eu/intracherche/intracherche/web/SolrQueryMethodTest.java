package eu.intracherche.intracherche.web;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrRequest;
import org.apache.solr.client.solrj.impl.HttpJdkSolrClient;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A user in many AD groups produces an ACL filter longer than Jetty's 8 KB request-line limit.
 * Over GET, Solr answers "414 URI Too Long" and every search fails, so queries that carry the ACL
 * filters must go by POST.
 */
class SolrQueryMethodTest {

    /** {@code solrClient.query(core, params)} with no method argument sends a GET. */
    private static final Pattern GET_QUERY = Pattern.compile("\\bsolr(?:Client)?\\.query\\(([^;]*?)\\);");

    @Test
    void no_production_query_goes_by_get() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).collect(Collectors.toList())) {
                var matcher = GET_QUERY.matcher(Files.readString(file));
                while (matcher.find()) {
                    if (!matcher.group(1).contains("METHOD.POST")) {
                        offenders.add(file + ": " + matcher.group().strip());
                    }
                }
            }
        }
        assertThat(offenders).as("queries sent by GET").isEmpty();
    }

    @Tag("integration")
    @Test
    void a_very_long_filter_is_refused_by_get_and_accepted_by_post() throws Exception {
        try (SolrClient solr = new HttpJdkSolrClient.Builder(System.getProperty("test.solrUrl", "http://localhost:8983/solr")).build()) {
            String core = System.getProperty("test.solrCore", "Index_Demo_OCR");
            boolean up;
            try {
                up = solr.query(core, new SolrQuery("*:*").setRows(0)).getResults() != null;
            } catch (Exception e) {
                up = false;
            }
            assumeTrue(up, "demo Solr core " + core + " not available");

            String sids = java.util.stream.IntStream.range(0, 600)
                    .mapToObj(i -> "S-1-5-21-1334282048-2152953019-930347850-" + (10000 + i))
                    .collect(Collectors.joining(" OR "));
            SolrQuery query = new SolrQuery("*:*").setRows(0);
            query.addFilterQuery("id:(" + sids + ") OR *:*");

            assertThat(org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
                    () -> solr.query(core, query))).hasMessageContaining("414");
            assertThat(solr.query(core, query, SolrRequest.METHOD.POST).getStatus()).isZero();
        }
    }
}
