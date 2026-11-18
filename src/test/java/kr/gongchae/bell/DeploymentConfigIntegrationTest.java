package kr.gongchae.bell;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import jakarta.servlet.http.HttpServletRequest;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "management.server.port=0", "collector.enabled=false", "collector.backfill=false"
})
@Import(DeploymentConfigIntegrationTest.ProxyProbeController.class)
class DeploymentConfigIntegrationTest extends IntegrationTestSupport {
    @Autowired Environment environment;
    @Value("${local.server.port}") int appPort;
    @Value("${local.management.port}") int managementPort;

    @TestConfiguration
    static class ProxyProbeController {
        @RestController
        static class ProbeController {
            @GetMapping("/test-internal/proxy-probe")
            String probe(HttpServletRequest request) {
                return request.getRemoteAddr() + "|" + request.getScheme() + "|" + request.getServerName();
            }
        }
    }

    @Test
    void gracefulShutdownAndForwardHeadersDefaultsMatchAdrs() {
        assertThat(environment.getProperty("server.shutdown")).isEqualTo("graceful");
        assertThat(environment.getProperty("spring.lifecycle.timeout-per-shutdown-phase")).isEqualTo("30s");
        assertThat(environment.getProperty("spring.task.scheduling.shutdown.await-termination")).isEqualTo("true");
        assertThat(environment.getProperty("server.forward-headers-strategy")).isEqualTo("native");
        assertThat(environment.getProperty("collector.enabled")).isEqualTo("false");
    }

    @Test
    void trustedLoopbackProxyResolvesRightmostClientIpAndRejectsLeftmostSpoof() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            // Single hop from trusted loopback proxy -> honors client IP and forwarded proto/host.
            var singleHop = HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + appPort + "/test-internal/proxy-probe"))
                    .header("X-Forwarded-For", "203.0.113.10")
                    .header("X-Forwarded-Proto", "https")
                    .header("X-Forwarded-Host", "gongchae.enjoylcjworld.com")
                    .build();
            var singleResp = client.send(singleHop, HttpResponse.BodyHandlers.ofString());
            assertThat(singleResp.statusCode()).isEqualTo(200);
            assertThat(singleResp.body()).isEqualTo("203.0.113.10|https|gongchae.enjoylcjworld.com");

            // Spoofed multi-hop chain from untrusted client -> RemoteIpValve stops at rightmost untrusted IP.
            var spoofedChain = HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + appPort + "/test-internal/proxy-probe"))
                    .header("X-Forwarded-For", "198.51.100.99, 203.0.113.10")
                    .build();
            var spoofResp = client.send(spoofedChain, HttpResponse.BodyHandlers.ofString());
            assertThat(spoofResp.statusCode()).isEqualTo(200);
            assertThat(spoofResp.body()).startsWith("203.0.113.10|");

            // Management port exposes readiness & prometheus while public port blocks them.
            var mgmtHealth = client.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + managementPort + "/actuator/health/readiness")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(mgmtHealth.statusCode()).isEqualTo(200);
            assertThat(mgmtHealth.body()).contains("\"status\":\"UP\"");

            var mgmtPrometheus = client.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + managementPort + "/actuator/prometheus")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(mgmtPrometheus.statusCode()).isEqualTo(200);

            var appActuator = client.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + appPort + "/actuator/health/readiness")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(appActuator.statusCode()).isEqualTo(404);
        }
    }
}
