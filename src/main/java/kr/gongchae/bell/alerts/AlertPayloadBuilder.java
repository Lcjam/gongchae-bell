package kr.gongchae.bell.alerts;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.net.URLEncoder;
import java.util.List;
import java.util.StringJoiner;
import kr.gongchae.bell.alerts.AlertMatcher.ActiveSubscription;
import kr.gongchae.bell.alerts.AlertMatcher.PostingSummary;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Builds deterministic Resend JSON (with RFC 8058 List-Unsubscribe headers) and Discord webhook JSON (with
 * {@code allowed_mentions: {"parse": []}} and length capping). Uses only {@code batch_key} date labels, never
 * wall-clock {@code now()}, so retries after UNKNOWN produce identical payload SHA-256 hashes (contract 12 §3, 14-2).
 */
@Component
public class AlertPayloadBuilder {
    static final int DISCORD_CONTENT_LIMIT = 2000;
    private static final int DISCORD_MAX_LINES = 8;
    private static final int EMAIL_MAX_LINES_PER_SECTION = 20;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final AlertsProperties properties;
    private final AlertsCrypto crypto;
    private final String baseUrl;

    public AlertPayloadBuilder(AlertsProperties properties, AlertsCrypto crypto,
            @Value("${app.base-url:http://localhost:8080}") String baseUrl) {
        this.properties = properties;
        this.crypto = crypto;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    public byte[] build(String kind, String batchKey, ActiveSubscription sub,
            List<PostingSummary> deadlineItems, List<PostingSummary> newItems) {
        return switch (kind) {
            case "EMAIL_DIGEST" -> buildEmailDigest(batchKey, sub, deadlineItems, newItems);
            case "DISCORD_NEW" -> buildDiscord(sub, "새 공고 알림", batchLabel(batchKey), newItems);
            case "DISCORD_DEADLINE" -> buildDiscord(sub, "마감 임박 D-3 알림", batchLabel(batchKey), deadlineItems);
            default -> throw new IllegalArgumentException("NOT_AN_ASYNC_KIND");
        };
    }

    private byte[] buildEmailDigest(String batchKey, ActiveSubscription sub,
            List<PostingSummary> deadlineItems, List<PostingSummary> newItems) {
        int total = deadlineItems.size() + newItems.size();
        String dateLabel = batchLabel(batchKey);
        String manageUrl = baseUrl + "/subscriptions/manage?t="
                + crypto.capability(AlertsCrypto.Purpose.MANAGE, sub.id(), sub.tokenVersion());
        String unsubToken = crypto.capability(AlertsCrypto.Purpose.UNSUBSCRIBE, sub.id(), sub.tokenVersion());
        String unsubPageUrl = baseUrl + "/subscriptions/unsubscribe?t=" + unsubToken;
        String unsubOneClickUrl = baseUrl + "/subscriptions/unsubscribe/one-click?t=" + unsubToken;

        StringBuilder text = new StringBuilder();
        text.append("공채벨 ").append(dateLabel).append(" 알림 (총 ").append(total).append("건)\n");
        if (!deadlineItems.isEmpty()) {
            text.append("\n[마감 임박 D-3 · ").append(deadlineItems.size()).append("건]\n");
            appendEmailItems(text, deadlineItems);
        }
        if (!newItems.isEmpty()) {
            text.append("\n[새 공고 · ").append(newItems.size()).append("건]\n");
            appendEmailItems(text, newItems);
        }
        text.append("\n조건별 공고 목록: ").append(jobsLink(sub.conditions())).append('\n');
        text.append("\n구독 관리(조건 수정·해지): ").append(manageUrl).append('\n');
        text.append("바로 해지: ").append(unsubPageUrl).append('\n');

        ObjectNode body = JSON.createObjectNode();
        body.put("from", properties.mail().from());
        body.putArray("to").add(sub.email());
        body.put("subject", "[공채벨] " + dateLabel + " 맞춤 공고 알림 (" + total + "건)");
        ObjectNode headers = body.putObject("headers");
        headers.put("List-Unsubscribe", "<" + unsubOneClickUrl + ">");
        headers.put("List-Unsubscribe-Post", "List-Unsubscribe=One-Click");
        body.put("text", text.toString());
        return JSON.writeValueAsString(body).getBytes(UTF_8);
    }

    private void appendEmailItems(StringBuilder text, List<PostingSummary> items) {
        int shown = Math.min(items.size(), EMAIL_MAX_LINES_PER_SECTION);
        for (int i = 0; i < shown; i++) {
            PostingSummary item = items.get(i);
            text.append("- [").append(clean(item.instName(), 40)).append("] ")
                    .append(clean(item.title(), 120))
                    .append(" (마감 ").append(item.endDate()).append(") ")
                    .append(baseUrl).append("/jobs/").append(item.sn()).append('\n');
        }
        if (items.size() > shown) {
            text.append("  ... 외 ").append(items.size() - shown).append("건 (아래 목록 링크에서 전체 보기)\n");
        }
    }

    private byte[] buildDiscord(ActiveSubscription sub, String title, String batchLabel, List<PostingSummary> items) {
        String header = "[공채벨] " + title + " (" + batchLabel + " · " + items.size() + "건)\n";
        String footer = "전체 보기: " + jobsLink(sub.conditions());

        StringBuilder lines = new StringBuilder();
        int shown = 0;
        int limit = Math.min(items.size(), DISCORD_MAX_LINES);
        for (int i = 0; i < limit; i++) {
            PostingSummary item = items.get(i);
            String line = "- [" + clean(item.instName(), 30) + "] " + clean(item.title(), 80)
                    + " (마감 " + item.endDate() + ") " + baseUrl + "/jobs/" + item.sn() + "\n";
            int remaining = items.size() - (shown + 1);
            String overflowReserve = remaining > 0 ? "... 외 " + remaining + "건\n" : "";
            if (header.length() + lines.length() + line.length() + overflowReserve.length() + footer.length()
                    > DISCORD_CONTENT_LIMIT) {
                break;
            }
            lines.append(line);
            shown++;
        }
        StringBuilder content = new StringBuilder(header).append(lines);
        if (shown < items.size()) {
            content.append("... 외 ").append(items.size() - shown).append("건\n");
        }
        content.append(footer);
        String rendered = content.toString();
        if (rendered.length() > DISCORD_CONTENT_LIMIT) {
            rendered = header + items.size() + "건\n" + footer;
        }

        ObjectNode body = JSON.createObjectNode();
        body.put("content", rendered);
        body.putObject("allowed_mentions").putArray("parse");
        return JSON.writeValueAsString(body).getBytes(UTF_8);
    }

    String jobsLink(SubscriptionService.Conditions c) {
        StringJoiner query = new StringJoiner("&");
        addParam(query, "ncs", c.ncs());
        addParam(query, "region", c.region());
        addParam(query, "hire", c.hire());
        addParam(query, "career", c.career());
        addParam(query, "q", c.q());
        return query.length() == 0 ? baseUrl + "/jobs" : baseUrl + "/jobs?" + query;
    }

    private static void addParam(StringJoiner query, String name, String value) {
        if (value != null && !value.isBlank()) {
            query.add(name + "=" + URLEncoder.encode(value, UTF_8));
        }
    }

    private static String batchLabel(String batchKey) {
        if (batchKey.length() >= 16 && batchKey.contains("T")) {
            return batchKey.substring(0, 10) + " " + batchKey.substring(11, 16) + " KST";
        }
        return batchKey;
    }

    private static String clean(String value, int maxLen) {
        if (value == null) return "";
        String single = value.replaceAll("[\\r\\n]+", " ").trim();
        return single.length() > maxLen ? single.substring(0, maxLen) + "…" : single;
    }
}
