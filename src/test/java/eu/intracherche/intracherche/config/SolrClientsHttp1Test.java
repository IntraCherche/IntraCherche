package eu.intracherche.intracherche.config;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.impl.HttpJdkSolrClient;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.net.http.HttpClient;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Jetty closes a connection that has been idle for two minutes, and the JDK client's HTTP/2
 * upgrade then fails the next write with "Received RST_STREAM" — and its retries with it. A file
 * that is embedded for tens of minutes before its first write hit this every time.
 */
class SolrClientsHttp1Test {

    private final AppBeanConfig config = new AppBeanConfig();
    private final SolrConfig solr = new SolrConfig("http://localhost:8983/solr", "solr/configset");

    @Test
    void indexingClientSpeaksHttp1() throws Exception {
        assertEquals(HttpClient.Version.HTTP_1_1, versionOf(config.indexingSolrClient(solr)));
    }

    @Test
    void sharedClientSpeaksHttp1() throws Exception {
        assertEquals(HttpClient.Version.HTTP_1_1, versionOf(config.solrClient(solr)));
    }

    private static HttpClient.Version versionOf(SolrClient client) throws Exception {
        try (client) {
            Field f = HttpJdkSolrClient.class.getDeclaredField("httpClient");
            f.setAccessible(true);
            return ((HttpClient) f.get(client)).version();
        }
    }
}
