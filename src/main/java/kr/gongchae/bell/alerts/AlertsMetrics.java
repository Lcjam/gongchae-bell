package kr.gongchae.bell.alerts;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Durable DB gauges and bounded-label delivery counters/timers (PRD G2/G7/§6, ADR-0006, #15 M2 contract).
 * Never attaches emails, webhooks, tokens, or subscription IDs to tags.
 */
@Component
public class AlertsMetrics {
    private static final List<String> ALL_KINDS = List.of(
            "EMAIL_CONFIRM", "EMAIL_RECOVER", "EMAIL_DIGEST", "DISCORD_NEW", "DISCORD_DEADLINE", "DISCORD_TEST");
    private static final List<String> CHANNELS = List.of("EMAIL", "DISCORD");

    private final MeterRegistry registry;
    private final JdbcClient jdbc;
    private final Clock clock;
    private final AlertsProperties properties;
    private final AlertsCrypto crypto;
    private final RateLimiter limits;

    public AlertsMetrics(MeterRegistry registry, JdbcClient jdbc, Clock clock, AlertsProperties properties,
            AlertsCrypto crypto, RateLimiter limits) {
        this.registry = registry;
        this.jdbc = jdbc;
        this.clock = clock;
        this.properties = properties;
        this.crypto = crypto;
        this.limits = limits;

        // #15 monitoring M2 contract gauges
        Gauge.builder("alerts.notification.backlog", this, m -> m.countBacklog())
                .description("Async notifications waiting past 5m delay").register(registry);
        Gauge.builder("alerts.notification.sending.stuck", this, m -> m.countSendingStuck())
                .description("Notifications stuck in SENDING past 5m").register(registry);
        for (String kind : ALL_KINDS) {
            Gauge.builder("alerts.notification.failed.last1h", this, m -> m.countTerminal("FAILED", kind, Duration.ofHours(1)))
                    .tag("kind", kind).register(registry);
            Gauge.builder("alerts.notification.uncertain.last24h", this, m -> m.countTerminal("UNCERTAIN", kind, Duration.ofHours(24)))
                    .tag("kind", kind).register(registry);
            Gauge.builder("alerts.notification.sent.last24h", this, m -> m.countTerminal("SENT", kind, Duration.ofHours(24)))
                    .tag("kind", kind).register(registry);
            Gauge.builder("alerts.notification.duplicate.risk.last24h", this, m -> m.countDuplicateRiskLast24h(kind))
                    .tag("kind", kind).register(registry);
        }

        // PRD G2 & budget/delay gauges
        for (String channel : CHANNELS) {
            Gauge.builder("alerts.subscriptions.active", this, m -> m.countSubscriptions("ACTIVE", channel))
                    .tag("channel", channel).register(registry);
            Gauge.builder("alerts.subscriptions.disabled", this, m -> m.countSubscriptions("DISABLED", channel))
                    .tag("channel", channel).register(registry);
        }
        Gauge.builder("alerts.send.total.used", this, m -> m.sendTotalUsed("email", RateLimiter.EMAIL_TOTAL))
                .tag("provider", "email").register(registry);
        Gauge.builder("alerts.send.total.used", this, m -> m.sendTotalUsed("discord", RateLimiter.DISCORD_TOTAL))
                .tag("provider", "discord").register(registry);
        Gauge.builder("alerts.delivery.delay.max.seconds", this, m -> m.maxPendingAgeSeconds())
                .description("Max age in seconds of queued/sending async notifications").register(registry);
    }

    void recordAttemptOutcome(String kind, SendOutcome outcome) {
        Counter.builder("alerts.delivery.attempts")
                .tag("kind", kind)
                .tag("outcome", outcome.name())
                .register(registry)
                .increment();
    }

    void recordDuplicateRisk(String kind) {
        Counter.builder("alerts.delivery.duplicate.risk")
                .tag("kind", kind)
                .register(registry)
                .increment();
    }

    void recordDeliveryLatency(String kind, OffsetDateTime createdAt, OffsetDateTime finishedAt) {
        Duration d = Duration.between(createdAt, finishedAt);
        if (!d.isNegative()) {
            Timer.builder("alerts.delivery.latency").tag("kind", kind).register(registry).record(d);
        }
    }

    private int countBacklog() {
        return jdbc.sql("""
                SELECT count(*) FROM notification
                WHERE status IN ('PENDING', 'RETRY') AND next_attempt_at <= :cut
                """).param("cut", OffsetDateTime.now(clock).minusMinutes(5)).query(Integer.class).single();
    }

    private int countSendingStuck() {
        return jdbc.sql("""
                SELECT count(*) FROM notification
                WHERE status = 'SENDING' AND claimed_at < :cut
                """).param("cut", OffsetDateTime.now(clock).minusMinutes(5)).query(Integer.class).single();
    }

    private int countTerminal(String status, String kind, Duration window) {
        return jdbc.sql("""
                SELECT count(*) FROM notification
                WHERE status = :status AND kind = :kind AND finished_at > :cut
                """).param("status", status).param("kind", kind)
                .param("cut", OffsetDateTime.now(clock).minus(window)).query(Integer.class).single();
    }

    private int countDuplicateRiskLast24h(String kind) {
        return jdbc.sql("""
                SELECT count(*) FROM notification n
                WHERE n.status = 'SENT' AND n.kind = :kind AND n.finished_at > :cut
                  AND EXISTS (SELECT 1 FROM notification_attempt a
                              WHERE a.notification_id = n.id AND a.outcome IN (:unknown))
                """).param("kind", kind).param("cut", OffsetDateTime.now(clock).minusHours(24))
                .param("unknown", SendOutcome.UNKNOWN_NAMES).query(Integer.class).single();
    }

    private int countSubscriptions(String status, String channel) {
        return jdbc.sql("SELECT count(*) FROM subscription WHERE status = :status AND channel = :channel")
                .param("status", status).param("channel", channel).query(Integer.class).single();
    }

    private int sendTotalUsed(String provider, RateLimiter.Limit limit) {
        if (!properties.enabled()) return 0;
        return limits.count(RateLimiter.Scope.SEND_TOTAL, crypto.limitKey("SEND_TOTAL", provider), limit);
    }

    private double maxPendingAgeSeconds() {
        Double val = jdbc.sql("""
                SELECT COALESCE(EXTRACT(EPOCH FROM (:now - min(created_at))), 0)
                FROM notification
                WHERE status IN ('PENDING', 'RETRY', 'SENDING') AND kind IN (:kinds)
                """).param("now", OffsetDateTime.now(clock)).param("kinds", DeliveryLedger.ASYNC_KINDS)
                .query(Double.class).single();
        return val == null || val < 0 ? 0 : val;
    }
}
