package kr.gongchae.bell;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "management.server.port=0")
@AutoConfigureMockMvc
@Testcontainers
class FoundationIntegrationTest {
    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Value("${local.server.port}") int appPort;
    @Value("${local.management.port}") int managementPort;

    @Test
    void realHttpReadinessIsAvailableOnlyOnManagementPort() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            for (String path : new String[]{"/", "/jobs", "/robots.txt"}) {
                assertThat(client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + appPort + path)).build(),
                        HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
            }
            for (String path : new String[]{"/actuator/health/readiness", "/actuator/prometheus"}) {
                assertThat(client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + appPort + path)).build(),
                        HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(404);
            }
            var readiness = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + managementPort
                    + "/actuator/health/readiness")).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(readiness.statusCode()).isEqualTo(200);
            assertThat(readiness.body()).contains("\"status\":\"UP\"");
        }
    }

    @Test
    void migrationAndEmptyPagesWorkWithoutExternalServices() throws Exception {
        assertThat(jdbc.sql("SELECT count(*) FROM flyway_schema_history WHERE success").query(Long.class).single()).isEqualTo(2);
        mvc.perform(get("/")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("아직 완전 수집 기록이 없습니다")));
        mvc.perform(get("/jobs")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("아직 수집된 공고가 없습니다")));
        mvc.perform(get("/robots.txt")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Disallow: /subscriptions/")));
        mvc.perform(get("/does-not-exist")).andExpect(status().isNotFound());
        mvc.perform(get("/actuator/prometheus")).andExpect(status().isNotFound());
    }

    @Test
    void incompleteCollectionNeverAdvancesDisplayedTimestamp() throws Exception {
        jdbc.sql("DELETE FROM collect_run WHERE slot LIKE 'freshness-%'").update();
        jdbc.sql("""
                INSERT INTO collect_run(kind, slot, started_at, finished_at, result)
                VALUES ('REGULAR','freshness-complete','2026-11-01T00:00:00Z','2026-11-01T00:01:00Z','COMPLETE'),
                       ('REGULAR','freshness-incomplete','2026-11-02T00:00:00Z','2026-11-02T00:01:00Z','INCOMPLETE')
                """).update();
        try {
            mvc.perform(get("/")).andExpect(status().isOk())
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("2026.11.01 09:01 KST")))
                    .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("2026.11.02"))));
        } finally {
            jdbc.sql("DELETE FROM collect_run WHERE slot LIKE 'freshness-%'").update();
        }
    }

    @Test
    void databaseAllowsOnlyOneConcurrentOwnerOfCollectionSlot() throws Exception {
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Integer> claim = () -> {
                start.await(5, TimeUnit.SECONDS);
                return jdbc.sql("""
                        INSERT INTO collect_run(kind, slot, started_at)
                        VALUES ('REGULAR','concurrent-test',now())
                        ON CONFLICT (kind, slot) DO NOTHING
                        """).update();
            };
            var first = executor.submit(claim);
            var second = executor.submit(claim);
            start.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS) + second.get(10, TimeUnit.SECONDS)).isEqualTo(1);
        } finally {
            jdbc.sql("DELETE FROM collect_run WHERE slot = 'concurrent-test'").update();
        }
    }
}
