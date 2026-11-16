package kr.gongchae.bell.alerts;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Retention (ADR-0014, PRD FR-A6) and the synchronous-job sweep, every {@code alerts.maintenance-interval} (default
 * 5 minutes) in both apps; every statement is idempotent. Worst-case residency is the rule plus one interval while
 * the app runs (plus any downtime). Unsubscribe deletes immediately and is not done here; expiry and DISABLED
 * deletion are not written to the erasure journal (08).
 */
@Component
public class AlertsMaintenance {
    private static final Logger log = LoggerFactory.getLogger(AlertsMaintenance.class);
    static final Duration DISABLED_RETENTION = Duration.ofDays(30);
    static final Duration FINISHED_RETENTION = Duration.ofDays(30);

    private final JdbcClient jdbc;
    private final DeliveryLedger ledger;
    private final Clock clock;

    public AlertsMaintenance(JdbcClient jdbc, DeliveryLedger ledger, Clock clock) {
        this.jdbc = jdbc; this.ledger = ledger; this.clock = clock;
    }

    public record Removed(int pending, int disabled, int finishedJobs, int counters, int sweptSync) {}

    @Scheduled(fixedDelayString = "${alerts.maintenance-interval:PT5M}", initialDelayString = "${alerts.maintenance-interval:PT5M}")
    void scheduled() {
        try { run(); }
        catch (RuntimeException failure) { log.warn("ALERTS_MAINTENANCE_FAILED {}", failure.getClass().getSimpleName()); }
    }

    public Removed run() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        int pending = jdbc.sql("DELETE FROM subscription WHERE status = 'PENDING' AND confirm_expires_at <= :now")
                .param("now", now).update();
        int disabled = jdbc.sql("DELETE FROM subscription WHERE status = 'DISABLED' AND disabled_at <= :cut")
                .param("cut", now.minus(DISABLED_RETENTION)).update();
        int jobs = jdbc.sql("DELETE FROM notification WHERE finished_at <= :cut")
                .param("cut", now.minus(FINISHED_RETENTION)).update();
        int counters = jdbc.sql("DELETE FROM rate_limit WHERE window_end <= :now").param("now", now).update();
        int swept = ledger.sweepSync();
        return new Removed(pending, disabled, jobs, counters, swept);
    }
}
