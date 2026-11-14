package kr.gongchae.bell.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletRequest;

class CatalogUnitTest {

    @Test
    void safeUrlValidatorAllowsHttpHttpsAndNonStandardPortsAndBlocksMaliciousInputs() {
        assertThat(SafeUrlValidator.isSafeHttpUrl("https://www.knuch.kr:442/content/04info/11_01.asp?proc_type=view&b_num=5099"))
                .isTrue();
        assertThat(SafeUrlValidator.isSafeHttpUrl("http://example.go.kr/recruit")).isTrue();
        assertThat(SafeUrlValidator.isSafeHttpUrl("HTTPS://EXAMPLE.COM/path")).isTrue();

        assertThat(SafeUrlValidator.isSafeHttpUrl("javascript:alert(1)")).isFalse();
        assertThat(SafeUrlValidator.isSafeHttpUrl("JAVASCRIPT:alert(1)")).isFalse();
        assertThat(SafeUrlValidator.isSafeHttpUrl("data:text/html,<script>alert(1)</script>")).isFalse();
        assertThat(SafeUrlValidator.isSafeHttpUrl("vbscript:msgbox(1)")).isFalse();
        assertThat(SafeUrlValidator.isSafeHttpUrl("//evil.example/path")).isFalse();
        assertThat(SafeUrlValidator.isSafeHttpUrl("ftp://example.com/file")).isFalse();
        assertThat(SafeUrlValidator.isSafeHttpUrl("https://user:pass@example.com/path")).isFalse();
        assertThat(SafeUrlValidator.isSafeHttpUrl("https://example.com/a\r\nSet-Cookie: x=1")).isFalse();
        assertThat(SafeUrlValidator.isSafeHttpUrl("https://example.com/a\0b")).isFalse();
        assertThat(SafeUrlValidator.isSafeHttpUrl("   ")).isFalse();
        assertThat(SafeUrlValidator.isSafeHttpUrl(null)).isFalse();
    }

    @Test
    void kstClockDeterminesDdayAndEffectiveStatusAcrossUtcMidnightBoundary() {
        Clock kstMorning = Clock.fixed(Instant.parse("2026-11-14T23:30:00Z"), ZoneId.of("Asia/Seoul"));
        LocalDate kstToday = LocalDate.now(kstMorning);
        assertThat(kstToday).isEqualTo(LocalDate.of(2026, 11, 15));

        assertThat(CatalogViews.effectiveStatus("OPEN", LocalDate.of(2026, 11, 15), kstToday)).isEqualTo("OPEN");
        assertThat(CatalogViews.ddayLabel("OPEN", LocalDate.of(2026, 11, 15), kstToday)).isEqualTo("D-0");
        assertThat(CatalogViews.ddayLabel("OPEN", LocalDate.of(2026, 11, 18), kstToday)).isEqualTo("D-3");

        // Even if stored status remained OPEN due to failed ingestion, past end_date is treated as CLOSED
        assertThat(CatalogViews.effectiveStatus("OPEN", LocalDate.of(2026, 11, 14), kstToday)).isEqualTo("CLOSED");
        assertThat(CatalogViews.ddayLabel("CLOSED", LocalDate.of(2026, 11, 14), kstToday)).isEqualTo("마감");

        assertThat(CatalogViews.effectiveStatus("CHECK", LocalDate.of(2026, 11, 16), kstToday)).isEqualTo("CHECK");
        assertThat(CatalogViews.statusLabel("CHECK")).isEqualTo("확인 필요");
        assertThat(CatalogViews.ddayLabel("CHECK", LocalDate.of(2026, 11, 16), kstToday)).isEqualTo("D-1");
    }

    @Test
    void jobSearchCriteriaIgnoresUnknownAllowlistValuesAndEscapesLikeWildcards() {
        Map<String, Set<String>> allowed = Map.of(
                "NCS", Set.of("R600020"),
                "REGION", Set.of("R3010"),
                "HIRE", Set.of("R1010")
        );

        JobSearchCriteria normalized = JobSearchCriteria.normalize(
                "INVALID_NCS",
                "R3010",
                "INVALID_HIRE",
                "hacker",
                "badstatus",
                "  100%_test\\path  ",
                "badsort",
                "-3",
                allowed
        );

        assertThat(normalized.ncs()).isNull();
        assertThat(normalized.region()).isEqualTo("R3010");
        assertThat(normalized.hire()).isNull();
        assertThat(normalized.career()).isNull();
        assertThat(normalized.status()).isEqualTo("open");
        assertThat(normalized.sort()).isEqualTo("deadline");
        assertThat(normalized.page()).isEqualTo(1);
        assertThat(normalized.q()).isEqualTo("100%_test\\path");
        assertThat(normalized.likePattern()).isEqualTo("%100\\%\\_test\\\\path%");

        String overLong = "a".repeat(150);
        JobSearchCriteria truncated = JobSearchCriteria.normalize(
                null, null, null, "newcomer", "all", overLong, "begin", "abc", allowed);
        assertThat(truncated.career()).isEqualTo("newcomer");
        assertThat(truncated.status()).isEqualTo("all");
        assertThat(truncated.sort()).isEqualTo("begin");
        assertThat(truncated.page()).isEqualTo(1);
        assertThat(truncated.q()).hasSize(100);
    }

    @Test
    void metricsClassifiesUserBotAndInternalTraffic() {
        CatalogMetrics metrics = new CatalogMetrics(new SimpleMeterRegistry());

        var userReq = new MockHttpServletRequest("GET", "/jobs");
        userReq.addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)");
        assertThat(metrics.classifyTraffic(userReq)).isEqualTo(CatalogMetrics.TrafficType.USER);

        var botReq = new MockHttpServletRequest("GET", "/jobs");
        botReq.addHeader("User-Agent", "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)");
        assertThat(metrics.classifyTraffic(botReq)).isEqualTo(CatalogMetrics.TrafficType.BOT);

        var noUaReq = new MockHttpServletRequest("GET", "/jobs");
        assertThat(metrics.classifyTraffic(noUaReq)).isEqualTo(CatalogMetrics.TrafficType.BOT);

        var internalHeaderReq = new MockHttpServletRequest("GET", "/");
        internalHeaderReq.addHeader("User-Agent", "Java-http-client/21");
        internalHeaderReq.addHeader(CatalogMetrics.INTERNAL_HEADER, "true");
        assertThat(metrics.classifyTraffic(internalHeaderReq)).isEqualTo(CatalogMetrics.TrafficType.INTERNAL);

        var internalProbeReq = new MockHttpServletRequest("GET", "/");
        internalProbeReq.addHeader("User-Agent", "UptimeRobot/2.0");
        assertThat(metrics.classifyTraffic(internalProbeReq)).isEqualTo(CatalogMetrics.TrafficType.INTERNAL);
    }

    @Test
    void freshnessDetectsTwoHourDelayRecoversAndHandlesDbOutageWithoutThrowing() {
        ZoneId kst = ZoneId.of("Asia/Seoul");
        Instant now = Instant.parse("2026-11-15T00:00:00Z"); // 09:00:00 KST
        Clock clock = Clock.fixed(now, kst);
        JdbcClient failingJdbc = mock(JdbcClient.class);
        when(failingJdbc.sql(anyString())).thenThrow(new TransientDataAccessResourceException("simulated outage"));

        FreshnessService service = new FreshnessService(failingJdbc, clock);

        var empty = service.evaluate(Optional.empty(), now);
        assertThat(empty.display()).isEqualTo(FreshnessService.EMPTY_DISPLAY);
        assertThat(empty.delayed()).isFalse();

        // Exactly 2 hours (07:00 KST -> 09:00 KST) is not yet delayed
        var boundary = service.evaluate(
                Optional.of(OffsetDateTime.of(2026, 11, 15, 7, 0, 0, 0, ZoneOffset.ofHours(9))),
                now);
        assertThat(boundary.display()).isEqualTo("2026.11.15 07:00 KST");
        assertThat(boundary.delayed()).isFalse();

        // Over 2 hours (06:59 KST -> 09:00 KST) is flagged as delayed while keeping COMPLETE timestamp
        var delayed = service.evaluate(
                Optional.of(OffsetDateTime.of(2026, 11, 15, 6, 59, 0, 0, ZoneOffset.ofHours(9))),
                now);
        assertThat(delayed.display()).isEqualTo("2026.11.15 06:59 KST");
        assertThat(delayed.delayed()).isTrue();

        // Recovered (08:55 KST -> 09:00 KST) clears delay notice
        var recovered = service.evaluate(
                Optional.of(OffsetDateTime.of(2026, 11, 15, 8, 55, 0, 0, ZoneOffset.ofHours(9))),
                now);
        assertThat(recovered.display()).isEqualTo("2026.11.15 08:55 KST");
        assertThat(recovered.delayed()).isFalse();

        // Safe wrapper swallows DB errors during error page dispatch
        var unavailable = service.currentStatusSafe();
        assertThat(unavailable.display()).isEqualTo(FreshnessService.UNAVAILABLE_DISPLAY);
        assertThat(unavailable.delayed()).isFalse();
        assertThat(unavailable.available()).isFalse();
    }
}
