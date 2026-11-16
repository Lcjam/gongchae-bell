package kr.gongchae.bell.ingestion.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import kr.gongchae.bell.IntegrationTestSupport;
import kr.gongchae.bell.ingestion.Counts;
import kr.gongchae.bell.ingestion.Failure;
import kr.gongchae.bell.ingestion.PostingIngestor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@Import(OfficialFixtureIntegrationTest.FixtureConfiguration.class)
@TestPropertySource(properties = {"collector.enabled=false", "collector.backfill=false"})
class OfficialFixtureIntegrationTest extends IntegrationTestSupport {
    private static final Instant OBSERVED = Instant.parse("2026-11-15T05:41:46Z");
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final AtomicInteger REQUESTS = new AtomicInteger();
    private static final AtomicReference<String> UNEXPECTED_REQUEST = new AtomicReference<>();
    private static final HttpServer SERVER = fixtureServer();

    @TestConfiguration
    static class FixtureConfiguration {
        @Bean @Primary Clock fixtureClock() { return Clock.fixed(OBSERVED, KST); }
        @Bean(destroyMethod = "close") HttpClient fixtureHttpClient() { return HttpClient.newHttpClient(); }
        @Bean @Primary
        RecruitmentClient fixtureClient(HttpClient fixtureHttpClient, ApiBudget budget, Clock clock) {
            return new RecruitmentClient(fixtureHttpClient, budget, "fixture-only",
                    URI.create("http://127.0.0.1:" + SERVER.getAddress().getPort() + "/"),
                    d -> {}, clock, Duration.ofSeconds(5));
        }
    }

    @Autowired PostingIngestor ingestor;
    @Autowired DetailCollector details;
    @Autowired BackfillCollector backfill;
    @Autowired CollectionRuns runs;
    @Autowired JdbcClient jdbc;
    @Autowired MockMvc mvc;

    @BeforeEach
    void prepare() {
        clean();
        REQUESTS.set(0);
        UNEXPECTED_REQUEST.set(null);
    }

    @AfterEach
    void finish() {
        clean();
        assertThat(UNEXPECTED_REQUEST.get()).isNull();
    }

    private void clean() {
        jdbc.sql("TRUNCATE posting_change, posting_code, posting, code_name, institution, collect_run, api_attempt, api_budget RESTART IDENTITY CASCADE")
                .update();
    }

    @AfterAll static void stopServer() { SERVER.stop(0); }

    @Test
    void official497ListPassesTheRealParserAndAtomicRegularApplyWithoutDetailFanout() throws Exception {
        String body = fixture("list");
        var parsed = ingestor.parsePage(body);
        assertThat(parsed.totalCount()).isEqualTo(497);
        assertThat(parsed.sns()).hasSize(497).contains(307032L);
        assertThat(count("posting")).isZero();

        long run = runs.claim("REGULAR", "official-full").orElseThrow();
        var result = ingestor.applyRegular(run, body, OBSERVED);
        assertThat(result.result()).isEqualTo("COMPLETE");
        assertThat(result.counts()).isEqualTo(new Counts(497, 497, 497, 0, 0));
        assertThat(count("posting")).isEqualTo(497);
        assertThat(count("posting WHERE detail_status='NONE'")).isEqualTo(497);
        assertThat(jdbc.sql("SELECT total_count, received, inserted FROM collect_run WHERE id=:id")
                .param("id", run).query().singleRow())
                .containsEntry("total_count", 497).containsEntry("received", 497).containsEntry("inserted", 497);
        assertThat(count("api_attempt")).isZero();
        assertThat(REQUESTS).hasValue(0);
    }

    @Test
    void officialDetailFlowsThroughCollectorBeansDatabaseAndHtmlAndSurvivesListReplay() throws Exception {
        // Select one unchanged real list row and adjust only totalCount, so collect() has one eligible posting.
        String onePosting = selectedListRow();
        long first = runs.claim("REGULAR", "official-selected").orElseThrow();
        assertThat(ingestor.applyRegular(first, onePosting, OBSERVED).result()).isEqualTo("COMPLETE");
        details.collect("official-selected");

        var saved = detailColumns();
        assertThat(saved).containsEntry("detail_status", "DONE");
        assertThat(jdbc.sql("SELECT jsonb_array_length(steps) AS steps, jsonb_array_length(files) AS files FROM posting WHERE sn=307032")
                .query().singleRow()).containsEntry("steps", 4).containsEntry("files", 3);
        var actual = JSON.readTree(fixture("detail")).path("result");
        assertThat(JSON.readTree((String) saved.get("steps"))).isEqualTo(actual.path("steps"));
        assertThat(JSON.readTree((String) saved.get("files"))).isEqualTo(actual.path("files"));
        assertThat(count("api_attempt WHERE kind='DETAIL' AND outcome='SUCCESS'")).isEqualTo(1);
        assertThat(REQUESTS).hasValue(1);

        String html = mvc.perform(get("/jobs/307032")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(html).contains("전형 정보 0", "전형 정보 1", "경쟁률: 0.0",
                "[우대조건]\n없음\n\n[우대내용]\n없음", freshnessDisplay())
                .doesNotContain("상세 정보 미수집", "recrutStepSn", "minStepSn", "href=\"javascript:");
        for (var file : actual.path("files")) {
            assertThat(html).contains(file.path("atchFileNm").asString(), file.path("url").asString());
        }
        assertThat(html).contains("/jobs/307032/source");
        assertThat(REQUESTS).hasValue(1); // Rendering must not call the API.

        long replay = runs.claim("REGULAR", "official-replay").orElseThrow();
        assertThat(ingestor.applyRegular(replay, onePosting, OBSERVED.plusSeconds(3600)).counts())
                .isEqualTo(new Counts(1, 1, 0, 0, 0));
        assertThat(detailColumns()).isEqualTo(saved);
        assertThat(count("posting_change")).isZero();
        details.collect("official-replay");
        assertThat(REQUESTS).hasValue(1);
    }

    @Test
    void officialEmptyListIsIncompleteForRegularButCompleteZeroForBackfill() throws Exception {
        long baseline = runs.claim("REGULAR", "before-empty").orElseThrow();
        ingestor.applyRegular(baseline, selectedListRow(), OBSERVED);
        var before = jdbc.sql("SELECT * FROM posting WHERE sn=307032").query().singleRow();
        OffsetDateTime completeAt = lastComplete();

        long emptyRun = runs.claim("REGULAR", "official-empty").orElseThrow();
        var result = ingestor.applyRegular(emptyRun, fixture("list-empty"), OBSERVED.plusSeconds(3600));
        assertThat(result.result()).isEqualTo("INCOMPLETE");
        assertThat(result.failure()).isEqualTo(Failure.EMPTY_SNAPSHOT);
        assertThat(lastComplete()).isEqualTo(completeAt);
        assertThat(jdbc.sql("SELECT result, error FROM collect_run WHERE id=:id").param("id", emptyRun).query().singleRow())
                .containsEntry("result", "INCOMPLETE").containsEntry("error", "EMPTY_SNAPSHOT");

        assertThat(backfill.collect("official-empty", new BackfillRange(LocalDate.of(2010, 1, 1), LocalDate.of(2010, 1, 31)), false))
                .isEqualTo(BackfillCollector.Outcome.COMPLETE);
        assertThat(count("collect_run WHERE kind='BACKFILL' AND result='COMPLETE' AND total_count=0 AND received=0 AND unique_count=0"))
                .isEqualTo(3); // command, scan and page each record a successful zero-row receipt.
        assertThat(jdbc.sql("SELECT * FROM posting WHERE sn=307032").query().singleRow()).isEqualTo(before);
        assertThat(lastComplete()).isEqualTo(completeAt);
        assertThat(count("api_attempt WHERE kind='BACKFILL' AND outcome='SUCCESS'")).isEqualTo(1);
        assertThat(REQUESTS).hasValue(1);
    }

    private static String selectedListRow() throws IOException {
        var source = JSON.readTree(fixture("list"));
        ObjectNode selected = JSON.createObjectNode().put("resultCode", 200).put("totalCount", 1);
        var rows = selected.putArray("result");
        source.path("result").forEach(row -> { if (row.path("recrutPblntSn").asLong() == 307032) rows.add(row); });
        assertThat(rows.size()).isEqualTo(1);
        return JSON.writeValueAsString(selected);
    }

    private Map<String, Object> detailColumns() {
        return jdbc.sql("SELECT detail_status, steps::text, files::text, detail_collected_at FROM posting WHERE sn=307032")
                .query().singleRow();
    }

    private OffsetDateTime lastComplete() {
        return jdbc.sql("SELECT max(finished_at) FROM collect_run WHERE kind='REGULAR' AND result='COMPLETE'")
                .query(OffsetDateTime.class).single();
    }

    private String freshnessDisplay() {
        return lastComplete().atZoneSameInstant(KST).format(DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm 'KST'"));
    }

    private int count(String tableAndCondition) {
        return jdbc.sql("SELECT count(*) FROM " + tableAndCondition).query(Integer.class).single();
    }

    private static String fixture(String name) throws IOException {
        return Files.readString(Path.of("fixtures/alio-official-" + name + "-sample.json"), StandardCharsets.UTF_8);
    }

    private static HttpServer fixtureServer() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                REQUESTS.incrementAndGet();
                String path = exchange.getRequestURI().getPath();
                String query = exchange.getRequestURI().getRawQuery();
                boolean detail = path.equals("/detail") && query.contains("sn=307032");
                // Initial load sends the start lower bound only; a pbancEndYmd would filter by deadline upstream.
                boolean empty = path.equals("/list") && query.contains("pbancBgngYmd=2010-01-01")
                        && !query.contains("pbancEndYmd") && query.contains("pageNo=1");
                if (!detail && !empty) UNEXPECTED_REQUEST.set(path + "?" + query);
                byte[] bytes = (detail ? fixture("detail") : empty ? fixture("list-empty") : "{}").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                exchange.sendResponseHeaders(detail || empty ? 200 : 400, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
