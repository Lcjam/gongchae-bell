package kr.gongchae.bell.ops;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.TreeMap;
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
    @Autowired MeterRegistry registry;
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

    /**
     * Root P2: an alert POST may run for its whole 25 s budget. The last finite bucket must hold that, or the
     * dashboard p95 (histogram_quantile) reads the last bound (it was 10 s). Synthetic 20 s requests are recorded on
     * the real http.server.requests meter (same name and tag keys Spring uses) and read back from the real scrape.
     */
    @Test
    void theP95BucketsHoldTheLongestSupportedRequest() throws Exception {
        String scrape;
        try (var client = HttpClient.newHttpClient()) {
            client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + appPort + "/")).build(), HttpResponse.BodyHandlers.discarding());
            Timer spring = registry.find("http.server.requests").timers().iterator().next();
            Timer synthetic = registry.timer("http.server.requests",
                    Tags.of(spring.getId().getTags()).and("uri", "/p95-probe").and("method", "POST"));
            for (int i = 0; i < 20; i++) synthetic.record(Duration.ofSeconds(20));
            scrape = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + managementPort + "/actuator/prometheus"))
                    .build(), HttpResponse.BodyHandlers.ofString()).body();
        }
        var buckets = new TreeMap<Double, Double>();  // le -> cumulative count
        Pattern.compile("(?m)^http_server_requests_seconds_bucket\\{(?=[^}]*uri=\"/p95-probe\")[^}]*le=\"([^\"]+)\"} (\\S+)$")
                .matcher(scrape).results()
                .forEach(m -> buckets.put(m.group(1).equals("+Inf") ? Double.POSITIVE_INFINITY : Double.parseDouble(m.group(1)),
                        Double.parseDouble(m.group(2))));
        double lastFinite = buckets.headMap(Double.POSITIVE_INFINITY).lastKey();
        assertThat(lastFinite).as("last finite bucket covers the 25 s budget").isGreaterThanOrEqualTo(25.0);
        assertThat(buckets.get(Double.POSITIVE_INFINITY)).isEqualTo(20.0);
        assertThat(histogramQuantile(0.95, buckets)).as("p95 of 20 s requests, not the 10 s bound")
                .isGreaterThan(17.0).isLessThanOrEqualTo(lastFinite);
        // The promtool case evaluates the panel's own query on these bounds: keep both in step.
        var panelQuery = Pattern.compile("\"expr\": \"(histogram_quantile\\(0\\.95,[^\"\\\\]*(?:\\\\.[^\"\\\\]*)*)\"")
                .matcher(Files.readString(Path.of("deploy/monitoring/grafana/dashboards/gongchae.json")));
        assertThat(panelQuery.find()).isTrue();
        String promtool = Files.readString(Path.of("deploy/monitoring/prometheus/tests/gongchae.test.yml"));
        assertThat(promtool).contains("expr: " + panelQuery.group(1).replace("\\\"", "\""));
        for (Double bound : buckets.headMap(Double.POSITIVE_INFINITY).tailMap(17.0).keySet())
            assertThat(promtool).as("bucket bound %s", bound).contains("le=\"" + bound + "\"");
    }

    /** Prometheus histogram_quantile on one series of cumulative buckets (linear within the bucket). */
    static double histogramQuantile(double q, TreeMap<Double, Double> buckets) {
        double rank = q * buckets.lastEntry().getValue(), lower = 0, below = 0;
        for (var bucket : buckets.entrySet()) {
            if (bucket.getValue() >= rank) {
                if (bucket.getKey().isInfinite()) return lower;  // past the last finite bucket: its bound
                return lower + (bucket.getKey() - lower) * (rank - below) / (bucket.getValue() - below);
            }
            lower = bucket.getKey();
            below = bucket.getValue();
        }
        throw new IllegalStateException("no +Inf bucket");
    }
}
