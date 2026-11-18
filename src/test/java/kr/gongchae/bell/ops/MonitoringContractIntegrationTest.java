package kr.gongchae.bell.ops;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import kr.gongchae.bell.IntegrationTestSupport;
import kr.gongchae.bell.catalog.CatalogMetrics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mock.web.MockHttpServletRequest;

/** Every app metric the Prometheus rules and Grafana dashboard read must exist in the real scrape, with safe labels. */
class MonitoringContractIntegrationTest extends IntegrationTestSupport {
    static final Pattern METRIC = Pattern.compile("\\b(?:gongchae|collector|http_server_requests)_[a-z0-9_]+");
    static final String SEARCH = "monitoringprobe7731";
    @Autowired CatalogMetrics catalogMetrics;
    @Value("${local.server.port}") int appPort;
    @Value("${local.management.port}") int managementPort;

    @Test
    void rulesAndDashboardsReadOnlyMetricsTheAppExposes() throws Exception {
        String scrape;
        try (var client = HttpClient.newHttpClient()) {
            for (String path : new String[]{"/", "/jobs?keyword=" + SEARCH, "/jobs/31337", "/" + SEARCH}) {
                client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + appPort + path)).build(),
                        HttpResponse.BodyHandlers.discarding());
            }
            // The source redirect counter is created on first use; a posting row is not what this test is about.
            catalogMetrics.recordSourceRedirect(false, new MockHttpServletRequest());
            scrape = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + managementPort + "/actuator/prometheus"))
                    .build(), HttpResponse.BodyHandlers.ofString()).body();
        }
        var referenced = new TreeSet<String>();
        try (Stream<Path> files = Files.walk(Path.of("deploy/monitoring"))) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                METRIC.matcher(Files.readString(file)).results().forEach(m -> referenced.add(m.group()));
            }
        }
        assertThat(referenced).contains("collector_regular_incomplete_consecutive", "collector_last_complete_epoch",
                "collector_api_attempts_today", "http_server_requests_seconds_bucket", "gongchae_page_views_total",
                "gongchae_source_redirects_total");
        for (String name : referenced) {
            assertThat(Pattern.compile("(?m)^" + name + "[{ ]").matcher(scrape).find()).as(name).isTrue();
        }
        assertThat(scrape).containsPattern("(?m)^http_server_requests_seconds_bucket\\{.*uri=\"/jobs\".*le=\"");
        assertThat(scrape).containsPattern("(?m)^collector_api_warning_limit 500(\\.0)?$");
        assertThat(scrape).containsPattern("(?m)^collector_api_stop_limit 900(\\.0)?$");
        // Labels carry route templates and fixed values, never a raw path, query or search term.
        assertThat(scrape).doesNotContain(SEARCH).doesNotContain("/jobs/31337").doesNotContain("keyword");
    }
}
