package kr.gongchae.bell.alerts;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import kr.gongchae.bell.alerts.AlertMatcher.ActiveSubscription;
import kr.gongchae.bell.alerts.AlertMatcher.PostingSummary;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class AlertPayloadBuilderTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private AlertPayloadBuilder builder() {
        var privacy = new AlertsProperties.Privacy("담당자", "c@example.com", "v1", "2026-11-18", "OCI", "consent", "30d");
        var props = new AlertsProperties(true, AlertsSecurityTest.KEY_A, AlertsSecurityTest.KEY_B,
                AlertsSecurityTest.KEY_C, "k1", "erasure.log",
                new AlertsProperties.Mail("re_test_fixture", "공채벨 <alerts@gongchae.example>", null), privacy);
        return new AlertPayloadBuilder(props, new AlertsCrypto(props), "https://gongchae.example");
    }

    @Test
    void emailDigestIncludesRfc8058HeadersLinksAndDeterministicHash() {
        var b = builder();
        UUID subId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        var cond = new SubscriptionService.Conditions("R100", null, null, "newcomer", "채용", true, true);
        var sub = new ActiveSubscription(subId, "EMAIL", "ACTIVE", "user@example.com", null, null, cond, 2,
                OffsetDateTime.of(2026, 11, 10, 0, 0, 0, 0, ZoneOffset.UTC));
        var deadline = List.of(new PostingSummary(101L, "한국공사", "신입 채용", LocalDate.of(2026, 11, 21)));
        var news = List.of(new PostingSummary(102L, "공공연구원", "IT 채용", LocalDate.of(2026, 11, 30)));

        byte[] first = b.build("EMAIL_DIGEST", "2026-11-18", sub, deadline, news);
        byte[] second = b.build("EMAIL_DIGEST", "2026-11-18", sub, deadline, news);
        assertThat(first).isEqualTo(second);

        JsonNode root = JSON.readTree(new String(first, UTF_8));
        assertThat(root.path("to").get(0).asString()).isEqualTo("user@example.com");
        assertThat(root.path("headers").path("List-Unsubscribe").asString())
                .startsWith("<https://gongchae.example/subscriptions/unsubscribe/one-click?t=" + subId + ".")
                .endsWith(">");
        assertThat(root.path("headers").path("List-Unsubscribe-Post").asString())
                .isEqualTo("List-Unsubscribe=One-Click");
        String text = root.path("text").asString();
        assertThat(text).contains("2026-11-18").contains("[마감 임박 D-3 · 1건]").contains("[새 공고 · 1건]")
                .contains("/subscriptions/manage?t=").contains("/subscriptions/unsubscribe?t=")
                .contains("https://gongchae.example/jobs?ncs=R100&career=newcomer&q=%EC%B1%84%EC%9A%A9");
    }

    @Test
    void discordPayloadCapsContentLengthDisablesMentionsAndNeverIncludesManageLinks() {
        var b = builder();
        UUID subId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        var cond = new SubscriptionService.Conditions(null, "R200", null, null, null, true, true);
        var sub = new ActiveSubscription(subId, "DISCORD", "ACTIVE", null, new byte[]{1}, "k1", cond, 1,
                OffsetDateTime.of(2026, 11, 10, 0, 0, 0, 0, ZoneOffset.UTC));
        List<PostingSummary> many = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            many.add(new PostingSummary(500L + i, "긴기관명테스트기관".repeat(3),
                    "아주긴공고제목@everyone<@123456>".repeat(5), LocalDate.of(2026, 11, 25)));
        }

        byte[] payload = b.build("DISCORD_NEW", "2026-11-18T10:00:00+09:00", sub, List.of(), many);
        JsonNode root = JSON.readTree(new String(payload, UTF_8));
        assertThat(root.path("allowed_mentions").path("parse").size()).isZero();
        String content = root.path("content").asString();
        assertThat(content.length()).isLessThanOrEqualTo(AlertPayloadBuilder.DISCORD_CONTENT_LIMIT);
        assertThat(content).contains("2026-11-18 10:00 KST").contains("... 외 ")
                .contains("https://gongchae.example/jobs?region=R200")
                .doesNotContain("/subscriptions/manage").doesNotContain("/subscriptions/unsubscribe");
    }
}
