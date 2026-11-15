package kr.gongchae.bell.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Controller;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
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

        @Controller
        static class TestErrorTriggerController {
            @GetMapping("/__test-error")
            String triggerError() {
                throw new IllegalStateException("simulated controller failure");
            }
        }
    }

    @LocalServerPort int port;
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
        insertCode(400002, "REGION", "R3013");
        insertCode(400002, "HIRE", "R1040");

        // 3) Started today and already CLOSED -> must STILL appear in Home "오늘 시작한 공고"
        insertPosting(400003, "C0099", "오늘시작 당일마감 공고", "2026-11-15", "2026-11-15",
                "R2020", "1명", 1L, "https://example.go.kr/400003", false, false, "CLOSED", "NONE", "[]", "[]");
        insertCode(400003, "REGION", "R3010");
        insertCode(400003, "HIRE", "R1040");

        // 4) Stale OPEN posting whose end_date < KST_TODAY (must NOT appear as open or closing soon)
        insertPosting(400004, "C0099", "어제마감 지연 공고", "2026-11-01", "2026-11-14",
                "R2010", "2명", 2L, "https://example.go.kr/400004", false, false, "OPEN", "NONE", "[]", "[]");

        // 5) Malicious XSS & javascript: URL + DONE detail using official swagger contract shape (not a live capture)
        insertPosting(400005, "C0099", "<script>alert('xss')</script> 100% 공개채용", "2026-11-10", "2026-11-25",
                "R2010", "5명", 5L, "javascript:alert(1)", false, false, "OPEN", "DONE",
                "[{\"sortNo\":1,\"recrutNope\":5,\"aplyNope\":30,\"cmpttRt\":\"6.0\",\"recrutPbancTtl\":\"미노출제목\",\"recrutStepSn\":77777,\"rsnOcrnYmd\":\"20261114\"}]",
                "[{\"atchFileNm\":\"모집요강.pdf\",\"url\":\"https://example.go.kr/files/guide.pdf\",\"recrutAtchFileNo\":1,\"sortNo\":1},{\"atchFileNm\":\"악성파일\",\"url\":\"javascript:alert(2)\"}]");
        jdbc.sql("UPDATE posting SET aply_qlfc = '<img src=x onerror=alert(1)>' WHERE sn = 400005").update();
        insertCode(400005, "NCS", "R600001");
        insertCode(400005, "NCS", "R600020");
        insertCode(400005, "REGION", "R3013");
        insertCode(400005, "HIRE", "R1010");

        // 6) Career-only (R2020) OPEN posting for C0661 (does not affect C0099 3-year counts) + unknown step/file JSON shape
        insertPosting(400006, "C0661", "경력전용 공개채용 공고", "2026-11-12", "2026-11-20",
                "R2020", "2명", 2L, "https://example.go.kr/400006", false, false, "OPEN", "DONE",
                "[{\"stepNm\":\"임의단계키\",\"raw\":\"RAW_SECRET_STEP\"}]",
                "[{\"recrutAtchFileNo\":99,\"raw\":\"RAW_SECRET_FILE\"}]");
        insertCode(400006, "NCS", "R600006");
        insertCode(400006, "REGION", "R3013");
        insertCode(400006, "HIRE", "R1040");

        // 7) Historical postings for C0099 across 3-year window + pagination (>20 items) + out-of-window
        // Exactly 3 years ago (2023-11-15): INCLUDED
        insertPosting(400100, "C0099", "3년 경계 포함 공고", "2023-11-15", "2023-11-25",
                "R2010", "4명", 4L, "https://example.go.kr/400100", false, false, "CLOSED", "NONE", "[]", "[]");
        insertCode(400100, "NCS", "R600002");

        // 3 years + 1 day ago (2023-11-14): EXCLUDED from 3-year institution stats
        insertPosting(400101, "C0099", "3년 이전 제외 공고", "2023-11-14", "2023-11-24",
                "R2010", "99명", 99L, "https://example.go.kr/400101", false, false, "CLOSED", "NONE", "[]", "[]");
        insertCode(400101, "NCS", "R600002");

        // Seed 20 more 2024/2025 postings for C0099 so institution history exceeds 1 page (total > 20)
        // Include one non-numeric recruit entry in 2025 (400200) to prove per-year exclusion tracking
        for (int i = 0; i < 20; i++) {
            long sn = 400200L + i;
            String bgng = (i < 10) ? "2025-05-" + String.format("%02d", i + 1) : "2024-03-" + String.format("%02d", i - 9);
            String end = (i < 10) ? "2025-05-25" : "2024-03-25";
            String nopeRaw = (i == 0) ? "별도공고" : "2명";
            Long nope = (i == 0) ? null : 2L;
            insertPosting(sn, "C0099", "기관이력 페이지 테스트 공고 " + i, bgng, end,
                    "R2010", nopeRaw, nope, "https://example.go.kr/" + sn, false, false, "CLOSED", "NONE", "[]", "[]");
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
                .andExpect(content().string(not(containsString("어제마감 지연 공고"))))
                // Internal milestone labels (M2) must never appear in user-facing UI
                .andExpect(content().string(containsString("알림 기능 준비 중")))
                .andExpect(content().string(not(containsString("M2"))));
    }

    @Test
    void jobsListFiltersSortPaginationAndAllowlistIgnoreWorkWithoutDuplicates() throws Exception {
        // 1) Multi-NCS postings 307027 & 400005 carry multiple NCS codes including R600020:
        // filtering R600020 + newcomer + open must return each matching sn ONCE in deadline ASC order
        String ncsHtml = mvc.perform(get("/jobs")
                        .param("ncs", "R600020")
                        .param("career", "newcomer")
                        .param("status", "open"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("이 조건으로 알림 받기 (준비 중)")))
                .andExpect(content().string(not(containsString("M2"))))
                .andReturn().getResponse().getContentAsString();
        assertThat(extractResultCount(ncsHtml)).isEqualTo(4L);
        assertThat(extractDesktopTableJobSns(ncsHtml)).containsExactly(400001L, 400002L, 400005L, 307027L);

        // 2) Region filter
        String regionHtml = mvc.perform(get("/jobs").param("region", "R3010").param("status", "open"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(extractResultCount(regionHtml)).isEqualTo(2L);
        assertThat(extractDesktopTableJobSns(regionHtml)).containsExactly(400001L, 307027L);

        // 3) Hire filter
        String hireHtml = mvc.perform(get("/jobs").param("hire", "R1010").param("status", "open"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(extractResultCount(hireHtml)).isEqualTo(2L);
        assertThat(extractDesktopTableJobSns(hireHtml)).containsExactly(400001L, 400005L);

        // 4) Career filters: newonly (R2010 only) vs career (R2020 only) vs newcomer (R2010 + R2030)
        String newOnlyHtml = mvc.perform(get("/jobs").param("career", "newonly").param("status", "open"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(extractResultCount(newOnlyHtml)).isEqualTo(2L);
        assertThat(extractDesktopTableJobSns(newOnlyHtml)).containsExactly(400001L, 400005L);

        String careerHtml = mvc.perform(get("/jobs").param("career", "career").param("status", "open"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(extractResultCount(careerHtml)).isEqualTo(1L);
        assertThat(extractDesktopTableJobSns(careerHtml)).containsExactly(400006L);

        // 5) Deadline sort exact order on open postings
        List<Long> expectedOpenDeadline = jdbc.sql("""
                        SELECT sn FROM posting
                        WHERE status IN ('OPEN', 'CHECK') AND end_date >= DATE '2026-11-15'
                        ORDER BY end_date ASC, sn ASC
                        """)
                .query(Long.class).list();
        String openDeadlineHtml = mvc.perform(get("/jobs").param("status", "open").param("sort", "deadline"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(extractResultCount(openDeadlineHtml)).isEqualTo(expectedOpenDeadline.size());
        assertThat(extractDesktopTableJobSns(openDeadlineHtml)).containsExactlyElementsOf(expectedOpenDeadline);

        // 6) Unknown filter values ignored cleanly; "%" literal in q matches only literal "%" title (400005)
        String queryHtml = mvc.perform(get("/jobs")
                        .param("ncs", "INVALID_CODE")
                        .param("region", "INVALID_REGION")
                        .param("hire", "INVALID_HIRE")
                        .param("career", "invalid_career")
                        .param("sort", "invalid_sort")
                        .param("status", "invalid_status")
                        .param("page", "-5")
                        .param("q", "100%"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(extractResultCount(queryHtml)).isEqualTo(1L);
        assertThat(extractDesktopTableJobSns(queryHtml)).containsExactly(400005L);

        // 7) Begin DESC sort & multi-page pagination: zero overlap and full DB coverage
        List<Long> expectedAllBegin = jdbc.sql("SELECT sn FROM posting ORDER BY bgng_date DESC, sn DESC")
                .query(Long.class).list();
        String page1Html = mvc.perform(get("/jobs").param("status", "all").param("sort", "begin").param("page", "1"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String page2Html = mvc.perform(get("/jobs").param("status", "all").param("sort", "begin").param("page", "2"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("status=all")))
                .andExpect(content().string(containsString("sort=begin")))
                .andReturn().getResponse().getContentAsString();

        List<Long> page1Sns = extractDesktopTableJobSns(page1Html);
        List<Long> page2Sns = extractDesktopTableJobSns(page2Html);
        assertThat(extractResultCount(page1Html)).isEqualTo(expectedAllBegin.size());
        assertThat(page1Sns).hasSize(JobSearchCriteria.PAGE_SIZE);
        assertThat(page2Sns).hasSize(expectedAllBegin.size() - JobSearchCriteria.PAGE_SIZE);
        assertThat(new HashSet<>(page1Sns)).doesNotContainAnyElementsOf(page2Sns);
        List<Long> combined = new ArrayList<>(page1Sns);
        combined.addAll(page2Sns);
        assertThat(combined).containsExactlyElementsOf(expectedAllBegin);
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

        // XSS payload + javascript: src_url + DONE detail with verified swagger step fields and safe/unsafe files
        mvc.perform(get("/jobs/400005"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("&lt;script&gt;alert(&#39;xss&#39;)&lt;/script&gt;")))
                .andExpect(content().string(not(containsString("<script>alert('xss')</script>"))))
                .andExpect(content().string(not(containsString("<img src=x"))))
                .andExpect(content().string(containsString("전형 정보 1")))
                .andExpect(content().string(containsString("모집 인원: 5명")))
                .andExpect(content().string(containsString("지원자 수: 30명")))
                .andExpect(content().string(containsString("경쟁률: 6.0")))
                .andExpect(content().string(not(containsString("미노출제목"))))
                .andExpect(content().string(not(containsString("77777"))))
                .andExpect(content().string(not(containsString("20261114"))))
                .andExpect(content().string(containsString("https://example.go.kr/files/guide.pdf")))
                .andExpect(content().string(not(containsString("href=\"javascript:"))))
                .andExpect(content().string(not(containsString("/jobs/400005/source"))));

        // Unknown step/file JSON shape must not dump raw field values
        mvc.perform(get("/jobs/400006"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("전형 단계 정보의 형식을 확인할 수 없어 표시하지 않았습니다.")))
                .andExpect(content().string(containsString("첨부파일 정보의 형식을 확인할 수 없어 표시하지 않았습니다.")))
                .andExpect(content().string(not(containsString("RAW_SECRET_STEP"))))
                .andExpect(content().string(not(containsString("RAW_SECRET_FILE"))))
                .andExpect(content().string(not(containsString("임의단계키"))));

        mvc.perform(get("/jobs/400005/source")).andExpect(status().isNotFound());
        mvc.perform(get("/jobs/999999")).andExpect(status().isNotFound());
        mvc.perform(get("/jobs/not-a-number")).andExpect(status().isNotFound());
        mvc.perform(get("/jobs/not-a-number/source")).andExpect(status().isNotFound());
    }

    @Test
    void institutionPageShowsYearlyRecruitsDistinctNcsAndPaginatedThreeYearHistory() throws Exception {
        // Future-start boundary (tomorrow = 2026-11-16): visible on /jobs, excluded from [today-3y, today] institution stats
        insertPosting(400102, "C0099", "내일 시작 예정 공고", "2026-11-16", "2026-11-30",
                "R2010", "7명", 7L, "https://example.go.kr/400102", false, false, "OPEN", "NONE", "[]", "[]");
        mvc.perform(get("/jobs").param("status", "open").param("q", "내일 시작"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("내일 시작 예정 공고")))
                .andExpect(content().string(containsString("/jobs/400102")));

        // C0099 has 26 postings in [2023-11-15 .. 2026-11-15] (400101 on 2023-11-14 and 400102 on 2026-11-16 excluded):
        //   2026: 5 postings (400001:3, 400002:null, 400003:1, 400004:2, 400005:5) -> 5건, 11명, 제외 1건
        //   2025: 10 postings (400200:null + 9x 2명) -> 10건, 18명, 제외 1건
        //   2024: 10 postings (10x 2명) -> 10건, 20명, 제외 0건
        //   2023: 1 posting (400100:4) -> 1건, 4명, 제외 0건
        //   Total = 26 postings, 53명, 2건 excluded.
        //   NCS counts (DISTINCT sn): R600020=23, R600001=1 (400005 belongs to both R600020 & R600001), R600002=1 (400100)
        String instHtml = mvc.perform(get("/institutions/C0099"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("2023-11-15 ~ 2026-11-15, 오늘까지 시작한 공고 기준")))
                .andExpect(content().string(containsString("시작일이 도래하기 전까지 이력 통계와 목록에는 포함되지 않습니다")))
                .andExpect(content().string(containsString("26건")))
                .andExpect(content().string(containsString("53명")))
                .andExpect(content().string(containsString("숫자로 확인되지 않은 공고 2건 제외")))
                .andExpect(content().string(not(containsString("실제 채용 실적"))))
                .andExpect(content().string(containsString("다중 직무가 지정된 공고는 각 직무에 모두 포함")))
                .andExpect(content().string(not(containsString("3년 이전 제외 공고"))))
                .andExpect(content().string(not(containsString("내일 시작 예정 공고"))))
                .andReturn().getResponse().getContentAsString();

        assertThat(extractTableRows(instHtml, "yearly-recruit-heading")).containsExactly(
                List.of("2026년", "5건", "11명", "1건"),
                List.of("2025년", "10건", "18명", "1건"),
                List.of("2024년", "10건", "20명", "0건"),
                List.of("2023년", "1건", "4명", "0건")
        );
        assertThat(extractTableRows(instHtml, "ncs-dist-heading")).containsExactly(
                List.of("R600020", "정보통신", "23건"),
                List.of("R600001", "사업관리", "1건"),
                List.of("R600002", "경영.회계.사무", "1건")
        );

        // Page 1 (20) & Page 2 (6) of 3-year institution history are disjoint and reach 2023-11-15 boundary
        List<Long> page1History = extractTableJobLinks(instHtml, "history-heading");
        String instPage2Html = mvc.perform(get("/institutions/C0099").param("page", "2"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("3년 경계 포함 공고")))
                .andExpect(content().string(not(containsString("3년 이전 제외 공고"))))
                .andReturn().getResponse().getContentAsString();
        List<Long> page2History = extractTableJobLinks(instPage2Html, "history-heading");

        assertThat(page1History).hasSize(20);
        assertThat(page2History).hasSize(6);
        assertThat(new HashSet<>(page1History)).doesNotContainAnyElementsOf(page2History);
        assertThat(page2History).contains(400100L).doesNotContain(400101L);

        mvc.perform(get("/institutions/UNKNOWN")).andExpect(status().isNotFound());
    }

    @Test
    void metricsErrorDispatchFreshnessAndDelayRecoveryWorkEndToEnd() throws Exception {
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
        double detailViewBefore = counterValue("gongchae.page.views", "page", "job_detail", "traffic", "user");
        double instViewBefore = counterValue("gongchae.page.views", "page", "institution", "traffic", "user");
        double redirectFalseBefore = counterValue("gongchae.source.redirects", "generic", "false", "traffic", "user");

        // 404 requests must NOT increment G2 counters
        mvc.perform(get("/jobs/999999").header("User-Agent", "Mozilla/5.0")).andExpect(status().isNotFound());
        mvc.perform(get("/jobs/not-a-number").header("User-Agent", "Mozilla/5.0")).andExpect(status().isNotFound());
        mvc.perform(get("/institutions/UNKNOWN").header("User-Agent", "Mozilla/5.0")).andExpect(status().isNotFound());
        mvc.perform(get("/jobs/999999/source").header("User-Agent", "Mozilla/5.0")).andExpect(status().isNotFound());
        mvc.perform(get("/jobs/400005/source").header("User-Agent", "Mozilla/5.0")).andExpect(status().isNotFound());
        assertThat(counterValue("gongchae.page.views", "page", "job_detail", "traffic", "user")).isEqualTo(detailViewBefore);
        assertThat(counterValue("gongchae.page.views", "page", "institution", "traffic", "user")).isEqualTo(instViewBefore);
        assertThat(counterValue("gongchae.source.redirects", "generic", "false", "traffic", "user")).isEqualTo(redirectFalseBefore);

        // Successful 200 detail/institution & 302 source requests DO increment counters
        mvc.perform(get("/jobs/307027").header("User-Agent", "Mozilla/5.0")).andExpect(status().isOk());
        mvc.perform(get("/institutions/C0099").header("User-Agent", "Mozilla/5.0")).andExpect(status().isOk());
        mvc.perform(get("/jobs/307027/source").header("User-Agent", "Mozilla/5.0")).andExpect(status().isFound());
        assertThat(counterValue("gongchae.page.views", "page", "job_detail", "traffic", "user")).isEqualTo(detailViewBefore + 1);
        assertThat(counterValue("gongchae.page.views", "page", "institution", "traffic", "user")).isEqualTo(instViewBefore + 1);
        assertThat(counterValue("gongchae.source.redirects", "generic", "true", "traffic", "user"))
                .isEqualTo(redirectGenericBefore + 1);

        mvc.perform(get("/sitemap.xml"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/xml"))
                .andExpect(content().string(containsString("<loc>https://gongchae.example/institutions/C0099</loc>")))
                .andExpect(content().string(containsString("<loc>https://gongchae.example/jobs/307028</loc>")));

        // Real HTTP Servlet container error dispatches (/error) carry existing COMPLETE freshness timestamp
        HttpClient http = HttpClient.newHttpClient();
        HttpResponse<String> boot404 = http.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/does-not-exist"))
                        .header("Accept", "text/html")
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(boot404.statusCode()).isEqualTo(404);
        assertThat(boot404.body()).contains("요청하신 페이지를 찾을 수 없습니다");
        assertThat(boot404.body()).contains("2026.11.15 08:07 KST");

        HttpResponse<String> boot500 = http.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/__test-error"))
                        .header("Accept", "text/html")
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(boot500.statusCode()).isEqualTo(500);
        assertThat(boot500.body()).contains("일시적인 오류가 발생했습니다");
        assertThat(boot500.body()).contains("2026.11.15 08:07 KST");

        // Delay > 2h after last COMPLETE + FAILED run keeps old COMPLETE timestamp and triggers delay banner/badge
        jdbc.sql("DELETE FROM collect_run").update();
        jdbc.sql("""
                INSERT INTO collect_run(kind, slot, started_at, finished_at, result) VALUES
                  ('REGULAR', '2026-11-15T06', '2026-11-14T21:50:00Z', '2026-11-14T21:55:00Z', 'COMPLETE'),
                  ('REGULAR', '2026-11-15T08', '2026-11-14T23:45:00Z', '2026-11-14T23:50:00Z', 'FAILED')
                """).update();

        mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("2026.11.15 06:55 KST")))
                .andExpect(content().string(containsString("수집 지연 안내:")))
                .andExpect(content().string(containsString("수집 지연")));

        HttpResponse<String> delayed404 = http.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/does-not-exist"))
                        .header("Accept", "text/html")
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(delayed404.statusCode()).isEqualTo(404);
        assertThat(delayed404.body()).contains("2026.11.15 06:55 KST");
        assertThat(delayed404.body()).contains("수집 지연");

        // Recovery COMPLETE within 2h clears delay banner/badge and advances displayed timestamp
        jdbc.sql("""
                INSERT INTO collect_run(kind, slot, started_at, finished_at, result)
                VALUES ('REGULAR', '2026-11-15T08-recover', '2026-11-14T23:51:00Z', '2026-11-14T23:55:00Z', 'COMPLETE')
                """).update();

        mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("2026.11.15 08:55 KST")))
                .andExpect(content().string(not(containsString("수집 지연"))));
    }

    @Test
    void jobsPageDoesNotClaimEmptyCollectionWhenOnlyClosedPostingsExist() throws Exception {
        jdbc.sql("UPDATE posting SET status = 'CLOSED', end_date = DATE '2026-11-10'").update();

        mvc.perform(get("/jobs"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("현재 진행 중인 공고가 없습니다")))
                .andExpect(content().string(containsString("/jobs?status=all")))
                .andExpect(content().string(not(containsString("아직 수집된 공고가 없습니다"))));

        mvc.perform(get("/jobs").param("status", "all"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("307027")));
    }

    private static List<Long> extractDesktopTableJobSns(String html) {
        int tbodyStart = html.indexOf("<tbody>");
        int tbodyEnd = html.indexOf("</tbody>", tbodyStart);
        assertThat(tbodyStart).isGreaterThan(-1);
        assertThat(tbodyEnd).isGreaterThan(tbodyStart);
        return extractJobLinks(html.substring(tbodyStart, tbodyEnd));
    }

    private static List<Long> extractTableJobLinks(String html, String sectionId) {
        String sectionHtml = extractSectionHtml(html, sectionId);
        int tbodyStart = sectionHtml.indexOf("<tbody>");
        int tbodyEnd = sectionHtml.indexOf("</tbody>", tbodyStart);
        assertThat(tbodyStart).isGreaterThan(-1);
        assertThat(tbodyEnd).isGreaterThan(tbodyStart);
        return extractJobLinks(sectionHtml.substring(tbodyStart, tbodyEnd));
    }

    private static List<Long> extractJobLinks(String fragment) {
        Matcher matcher = Pattern.compile("href=\"/jobs/(\\d+)\"").matcher(fragment);
        List<Long> sns = new ArrayList<>();
        while (matcher.find()) {
            sns.add(Long.parseLong(matcher.group(1)));
        }
        return sns;
    }

    private static long extractResultCount(String html) {
        Matcher matcher = Pattern.compile("검색 결과 <strong class=\"text-dark\">(\\d+)</strong>건").matcher(html);
        assertThat(matcher.find()).as("Expected search result count in HTML").isTrue();
        return Long.parseLong(matcher.group(1));
    }

    private static List<List<String>> extractTableRows(String html, String sectionId) {
        String sectionHtml = extractSectionHtml(html, sectionId);
        int tbodyStart = sectionHtml.indexOf("<tbody>");
        int tbodyEnd = sectionHtml.indexOf("</tbody>", tbodyStart);
        assertThat(tbodyStart).isGreaterThan(-1);
        String tbody = sectionHtml.substring(tbodyStart, tbodyEnd);
        List<List<String>> rows = new ArrayList<>();
        Matcher trMatcher = Pattern.compile("<tr[^>]*>(.*?)</tr>", Pattern.DOTALL).matcher(tbody);
        while (trMatcher.find()) {
            List<String> cells = new ArrayList<>();
            Matcher cellMatcher = Pattern.compile("<(?:th|td)[^>]*>(.*?)</(?:th|td)>", Pattern.DOTALL)
                    .matcher(trMatcher.group(1));
            while (cellMatcher.find()) {
                cells.add(cellMatcher.group(1).replaceAll("<[^>]+>", "").trim());
            }
            rows.add(cells);
        }
        return rows;
    }

    private static String extractSectionHtml(String html, String sectionId) {
        String marker = "aria-labelledby=\"" + sectionId + "\"";
        int start = html.indexOf(marker);
        assertThat(start).as("Missing section " + sectionId).isGreaterThan(-1);
        int end = html.indexOf("</section>", start);
        assertThat(end).isGreaterThan(start);
        return html.substring(start, end);
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
