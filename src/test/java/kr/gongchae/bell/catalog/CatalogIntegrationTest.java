package kr.gongchae.bell.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.micrometer.core.instrument.MeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "management.server.port=0",
                "app.base-url=https://gongchae.example"
        }
)
@AutoConfigureMockMvc
@Testcontainers
class CatalogIntegrationTest {
    private static final LocalDate KST_TODAY = LocalDate.of(2026, 11, 15);

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock fixedTestClock() {
            // 2026-11-15T09:00:00+09:00
            return Clock.fixed(Instant.parse("2026-11-15T00:00:00Z"), ZoneId.of("Asia/Seoul"));
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired MeterRegistry meterRegistry;
    @Autowired ObjectMapper objectMapper;

    @BeforeEach
    void seedDatabase() throws Exception {
        jdbc.sql("DELETE FROM posting_change").update();
        jdbc.sql("DELETE FROM posting_code").update();
        jdbc.sql("DELETE FROM posting").update();
        jdbc.sql("DELETE FROM code_name").update();
        jdbc.sql("DELETE FROM institution").update();
        jdbc.sql("DELETE FROM collect_run").update();

        jdbc.sql("""
                INSERT INTO collect_run(kind, slot, started_at, finished_at, result)
                VALUES ('REGULAR', '2026-11-15T08', '2026-11-14T23:00:00Z', '2026-11-14T23:07:00Z', 'COMPLETE')
                """).update();

        jdbc.sql("""
                INSERT INTO code_name(type, code, name) VALUES
                  ('NCS', 'R600001', '사업관리'),
                  ('NCS', 'R600002', '경영.회계.사무'),
                  ('NCS', 'R600006', '보건.의료'),
                  ('NCS', 'R600020', '정보통신'),
                  ('REGION', 'R3010', '서울'),
                  ('REGION', 'R3013', '대구'),
                  ('HIRE', 'R1010', '정규직'),
                  ('HIRE', 'R1040', '비정규직'),
                  ('EDU', 'R7010', '학력무관')
                """).update();

        jdbc.sql("""
                INSERT INTO institution(code, name) VALUES
                  ('C0008', '경북대학교병원'),
                  ('C0661', '식품안전정보원'),
                  ('C0099', '이력테스트공사')
                """).update();

        // Seed real fixture entries (307027 generic + multi-NCS, 307028 non-standard :442 port)
        JsonNode root = objectMapper.readTree(Files.readString(Path.of("fixtures/alio-public-list-sample.json")));
        JsonNode results = root.path("data").path("result");
        for (JsonNode item : results) {
            long sn = item.path("recrutPblntSn").asLong();
            if (sn == 307027 || sn == 307028) {
                insertPostingFromFixture(item, sn == 307027);
            }
        }

        // Additional boundary postings
        // 1) Closing in D-0 (OPEN) + started today
        insertPosting(400001, "C0099", "오늘시작 마감임박 D0 공고", "2026-11-15", "2026-11-15",
                "R2010", "3명", 3L, "https://example.go.kr/400001", false, false, "OPEN", "NONE", "[]", "[]");
        insertCode(400001, "NCS", "R600020");
        insertCode(400001, "REGION", "R3010");
        insertCode(400001, "HIRE", "R1010");

        // 2) Started today but CHECK status -> must appear in Home "오늘 시작한 공고", NOT in "마감 임박"
        insertPosting(400002, "C0099", "오늘시작 확인필요 공고", "2026-11-15", "2026-11-17",
                "R2030", "협의 후 결정", null, "https://example.go.kr/400002", false, true, "CHECK", "FAILED", "[]", "[]");
        insertCode(400002, "NCS", "R600020");

        // 3) Started today and already CLOSED -> must STILL appear in Home "오늘 시작한 공고"
        insertPosting(400003, "C0099", "오늘시작 당일마감 공고", "2026-11-15", "2026-11-15",
                "R2020", "1명", 1L, "https://example.go.kr/400003", false, false, "CLOSED", "NONE", "[]", "[]");

        // 4) Stale OPEN posting whose end_date < KST_TODAY (must NOT appear as open or closing soon)
        insertPosting(400004, "C0099", "어제마감 지연 공고", "2026-11-01", "2026-11-14",
                "R2010", "2명", 2L, "https://example.go.kr/400004", false, false, "OPEN", "NONE", "[]", "[]");

        // 5) Malicious XSS & javascript: URL + DONE detail with safe/unsafe files
        insertPosting(400005, "C0099", "<script>alert('xss')</script> 100% 공개채용", "2026-11-10", "2026-11-25",
                "R2010", "5명", 5L, "javascript:alert(1)", false, false, "OPEN", "DONE",
                "[{\"stepNm\":\"1차 서류\",\"stepCn\":\"지원서 평가\"}]",
                "[{\"atchFileNm\":\"모집요강.pdf\",\"url\":\"https://example.go.kr/files/guide.pdf\"},{\"atchFileNm\":\"악성파일\",\"url\":\"javascript:alert(2)\"}]");
        jdbc.sql("UPDATE posting SET aply_qlfc = '<img src=x onerror=alert(1)>' WHERE sn = 400005").update();
        insertCode(400005, "NCS", "R600001");
        insertCode(400005, "NCS", "R600020");

        // 6) Historical postings for C0099 across 3-year window + pagination (>20 items) + out-of-window
        // Exactly 3 years ago (2023-11-15): INCLUDED
        insertPosting(400100, "C0099", "3년 경계 포함 공고", "2023-11-15", "2023-11-25",
                "R2010", "4명", 4L, "https://example.go.kr/400100", false, false, "CLOSED", "NONE", "[]", "[]");
        insertCode(400100, "NCS", "R600002");

        // 3 years + 1 day ago (2023-11-14): EXCLUDED from 3-year institution stats
        insertPosting(400101, "C0099", "3년 이전 제외 공고", "2023-11-14", "2023-11-24",
                "R2010", "99명", 99L, "https://example.go.kr/400101", false, false, "CLOSED", "NONE", "[]", "[]");
        insertCode(400101, "NCS", "R600002");

        // Seed 20 more 2024/2025 postings for C0099 so institution history exceeds 1 page (total > 20)
        for (int i = 0; i < 20; i++) {
            long sn = 400200L + i;
            String bgng = (i < 10) ? "2025-05-" + String.format("%02d", i + 1) : "2024-03-" + String.format("%02d", i - 9);
            String end = (i < 10) ? "2025-05-25" : "2024-03-25";
            insertPosting(sn, "C0099", "기관이력 페이지 테스트 공고 " + i, bgng, end,
                    "R2010", "2명", 2L, "https://example.go.kr/" + sn, false, false, "CLOSED", "NONE", "[]", "[]");
            insertCode(sn, "NCS", "R600020");
        }
    }

    @Test
    void homeShowsClosingSoonAndStartedTodayIncludingClosedAndCheck() throws Exception {
        mvc.perform(get("/").header("User-Agent", "Mozilla/5.0"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("2026.11.15 08:07 KST")))
                .andExpect(content().string(containsString("<title>")))
                .andExpect(content().string(containsString("name=\"description\"")))
                .andExpect(content().string(containsString("오늘시작 마감임박 D0 공고")))
                // 307028 ends 2026-11-19 -> D-4 (not in D-0~D-3 closing soon)
                .andExpect(content().string(not(containsString("칠곡경북대학교병원 2026년 11월 2차"))))
                // Started today includes OPEN, CHECK, and CLOSED
                .andExpect(content().string(containsString("오늘시작 확인필요 공고")))
                .andExpect(content().string(containsString("오늘시작 당일마감 공고")))
                // Stale yesterday OPEN posting must not show in closing soon
                .andExpect(content().string(not(containsString("어제마감 지연 공고"))));
    }

    @Test
    void jobsListFiltersSortPaginationAndAllowlistIgnoreWorkWithoutDuplicates() throws Exception {
        // Multi-NCS posting 307027 & 400005 have R600020 and R600001: filtering R600020 returns distinct postings
        mvc.perform(get("/jobs").param("ncs", "R600020").param("career", "newcomer").param("status", "open"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("오늘시작 마감임박 D0 공고")))
                .andExpect(content().string(containsString("오늘시작 확인필요 공고")))
                .andExpect(content().string(containsString("식품안전정보원 2026년 제3차 기간제 직원 채용")))
                // R2020 career-only or stale ended excluded
                .andExpect(content().string(not(containsString("어제마감 지연 공고"))));

        // Unknown filter values ignored cleanly; "%" literal in q matches only literal "%" title (400005), not all rows
        mvc.perform(get("/jobs")
                        .param("ncs", "INVALID_CODE")
                        .param("career", "invalid_career")
                        .param("sort", "invalid_sort")
                        .param("status", "invalid_status")
                        .param("page", "-5")
                        .param("q", "100%"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("검색 결과 <strong class=\"text-dark\">1</strong>건")))
                .andExpect(content().string(containsString("공개채용")))
                .andExpect(content().string(not(containsString("오늘시작 마감임박 D0 공고"))));

        // Pagination preserves active filter query parameters
        mvc.perform(get("/jobs").param("status", "all").param("sort", "begin").param("page", "2"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("status=all")))
                .andExpect(content().string(containsString("sort=begin")));
    }

    @Test
    void jobDetailEscapesHtmlHandlesQualityAndValidatesSourceRedirect() throws Exception {
        // Fixture 307028 has non-standard :442 port -> allowed and redirected
        mvc.perform(get("/jobs/307028"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("칠곡경북대학교병원")))
                .andExpect(content().string(containsString("상세 정보 미수집")))
                .andExpect(content().string(containsString("/jobs/307028/source")));

        mvc.perform(get("/jobs/307028/source")
                        .param("url", "https://evil.example/phish")
                        .header("User-Agent", "Mozilla/5.0"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location",
                        "https://www.knuch.kr:442/content/04info/11_01.asp?proc_type=view&b_num=5099&rtn_url=%2Fcontent%2F04info%2F11%5F01%2Easp"));

        // Fixture 307027 is generic URL -> shows Alio detail fallback link
        mvc.perform(get("/jobs/307027"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("https://job.alio.go.kr/recruitview.do?idx=307027")));

        // XSS payload + javascript: src_url + DONE detail with mixed safe/unsafe files
        mvc.perform(get("/jobs/400005"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("&lt;script&gt;alert(&#39;xss&#39;)&lt;/script&gt;")))
                .andExpect(content().string(not(containsString("<script>alert('xss')</script>"))))
                .andExpect(content().string(not(containsString("<img src=x"))))
                .andExpect(content().string(containsString("1차 서류")))
                .andExpect(content().string(containsString("https://example.go.kr/files/guide.pdf")))
                .andExpect(content().string(not(containsString("href=\"javascript:"))))
                .andExpect(content().string(not(containsString("/jobs/400005/source"))));

        mvc.perform(get("/jobs/400005/source")).andExpect(status().isNotFound());
        mvc.perform(get("/jobs/999999")).andExpect(status().isNotFound());
        mvc.perform(get("/jobs/not-a-number")).andExpect(status().isNotFound());
        mvc.perform(get("/jobs/not-a-number/source")).andExpect(status().isNotFound());
    }

    @Test
    void institutionPageShowsYearlyRecruitsDistinctNcsAndPaginatedThreeYearHistory() throws Exception {
        // C0099 has 26 postings in [2023-11-15 .. 2026-11-15] (400101 on 2023-11-14 excluded):
        //   400001 (2026, 3명), 400002 (2026, null), 400003 (2026, 1명), 400004 (2026, 2명),
        //   400005 (2026, 5명), 10x 2025 (2명 each = 20명), 10x 2024 (2명 each = 20명), 400100 (2023, 4명)
        // Total = 26 postings, 55명, 1건 excluded.
        // NCS counts: R600020 = 23 (400001, 400002, 400005 + 20 paginated), R600001 = 1 (400005), R600002 = 1 (400100)
        // Sum of NCS counts = 25 (distinct sn per NCS, independent from total unique postings 26; multi-NCS 400005 counted in both R600020 & R600001)
        mvc.perform(get("/institutions/C0099"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("2023-11-15 ~ 2026-11-15")))
                .andExpect(content().string(containsString("26건")))
                .andExpect(content().string(containsString("55명")))
                .andExpect(content().string(containsString("제외")))
                .andExpect(content().string(not(containsString("실제 채용 실적"))))
                .andExpect(content().string(containsString("2026년")))
                .andExpect(content().string(containsString("2023년")))
                .andExpect(content().string(containsString("다중 직무가 지정된 공고는 각 직무에 모두 포함")))
                .andExpect(content().string(containsString("23건")))
                .andExpect(content().string(not(containsString("3년 이전 제외 공고"))));

        // Page 2 of institution history reaches older 2023-11-15 boundary posting
        mvc.perform(get("/institutions/C0099").param("page", "2"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("3년 경계 포함 공고")))
                .andExpect(content().string(not(containsString("3년 이전 제외 공고"))));

        mvc.perform(get("/institutions/UNKNOWN")).andExpect(status().isNotFound());
    }

    @Test
    void metricsSeparateUserBotAndInternalTrafficAndSitemapIsValid() throws Exception {
        double userBefore = counterValue("gongchae.page.views", "page", "jobs", "traffic", "user");
        double botBefore = counterValue("gongchae.page.views", "page", "jobs", "traffic", "bot");
        double internalBefore = counterValue("gongchae.page.views", "page", "jobs", "traffic", "internal");

        mvc.perform(get("/jobs").header("User-Agent", "Mozilla/5.0")).andExpect(status().isOk());
        mvc.perform(get("/jobs").header("User-Agent", "Googlebot/2.1")).andExpect(status().isOk());
        mvc.perform(get("/jobs").header(CatalogMetrics.INTERNAL_HEADER, "true").header("User-Agent", "Mozilla/5.0"))
                .andExpect(status().isOk());

        assertThat(counterValue("gongchae.page.views", "page", "jobs", "traffic", "user")).isEqualTo(userBefore + 1);
        assertThat(counterValue("gongchae.page.views", "page", "jobs", "traffic", "bot")).isEqualTo(botBefore + 1);
        assertThat(counterValue("gongchae.page.views", "page", "jobs", "traffic", "internal")).isEqualTo(internalBefore + 1);

        double redirectGenericBefore = counterValue("gongchae.source.redirects", "generic", "true", "traffic", "user");
        mvc.perform(get("/jobs/307027/source").header("User-Agent", "Mozilla/5.0")).andExpect(status().isFound());
        assertThat(counterValue("gongchae.source.redirects", "generic", "true", "traffic", "user"))
                .isEqualTo(redirectGenericBefore + 1);

        mvc.perform(get("/sitemap.xml"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/xml"))
                .andExpect(content().string(containsString("<loc>https://gongchae.example/institutions/C0099</loc>")))
                .andExpect(content().string(containsString("<loc>https://gongchae.example/jobs/307028</loc>")));
    }

    private double counterValue(String name, String... tags) {
        var counter = meterRegistry.find(name).tags(tags).counter();
        return counter == null ? 0.0 : counter.count();
    }

    private void insertPostingFromFixture(JsonNode item, boolean genericSrcUrl) {
        long sn = item.path("recrutPblntSn").asLong();
        String bgng = formatYmd(item.path("pbancBgngYmd").asString());
        String end = formatYmd(item.path("pbancEndYmd").asString());
        long nope = item.path("recrutNope").asLong();
        insertPosting(
                sn,
                item.path("pblntInstCd").asString(),
                item.path("recrutPbancTtl").asString(),
                bgng,
                end,
                item.path("recrutSe").asString(),
                nope + "명",
                nope,
                item.path("srcUrl").asString(),
                genericSrcUrl,
                false,
                "OPEN",
                "NONE",
                "[]",
                "[]"
        );
        for (String code : item.path("ncsCdLst").asString("").split(",")) {
            if (!code.isBlank()) {
                insertCode(sn, "NCS", code.trim());
            }
        }
        for (String code : item.path("workRgnLst").asString("").split(",")) {
            if (!code.isBlank()) {
                insertCode(sn, "REGION", code.trim());
            }
        }
        for (String code : item.path("hireTypeLst").asString("").split(",")) {
            if (!code.isBlank()) {
                insertCode(sn, "HIRE", code.trim());
            }
        }
    }

    private static String formatYmd(String yyyymmdd) {
        return yyyymmdd.substring(0, 4) + "-" + yyyymmdd.substring(4, 6) + "-" + yyyymmdd.substring(6, 8);
    }

    private void insertPosting(
            long sn,
            String instCode,
            String title,
            String bgngDate,
            String endDate,
            String recrutSe,
            String recrutNopeRaw,
            Long recrutNope,
            String srcUrl,
            boolean srcUrlGeneric,
            boolean brokenText,
            String status,
            String detailStatus,
            String stepsJson,
            String filesJson
    ) {
        jdbc.sql("""
                        INSERT INTO posting(
                          sn, inst_code, title, bgng_date, end_date, recrut_se,
                          recrut_nope_raw, recrut_nope, aply_qlfc, pref, scrnprcdr,
                          src_url, src_url_generic, broken_text, status, ongoing_yn,
                          from_backfill, content_hash, first_seen_at, last_seen_at,
                          detail_status, steps, files
                        ) VALUES (
                          :sn, :instCode, :title, CAST(:bgngDate AS date), CAST(:endDate AS date), :recrutSe,
                          :recrutNopeRaw, :recrutNope, '자격요건', '우대사항', '전형절차',
                          :srcUrl, :srcUrlGeneric, :brokenText, :status, 'Y',
                          false, 'hash', now(), now(),
                          :detailStatus, CAST(:steps AS jsonb), CAST(:files AS jsonb)
                        )
                        """)
                .param("sn", sn)
                .param("instCode", instCode)
                .param("title", title)
                .param("bgngDate", bgngDate)
                .param("endDate", endDate)
                .param("recrutSe", recrutSe)
                .param("recrutNopeRaw", recrutNopeRaw)
                .param("recrutNope", recrutNope)
                .param("srcUrl", srcUrl)
                .param("srcUrlGeneric", srcUrlGeneric)
                .param("brokenText", brokenText)
                .param("status", status)
                .param("detailStatus", detailStatus)
                .param("steps", stepsJson)
                .param("files", filesJson)
                .update();
    }

    private void insertCode(long sn, String type, String code) {
        jdbc.sql("INSERT INTO posting_code(sn, type, code) VALUES (:sn, :type, :code) ON CONFLICT DO NOTHING")
                .param("sn", sn)
                .param("type", type)
                .param("code", code)
                .update();
    }
}
