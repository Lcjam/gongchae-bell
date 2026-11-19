package kr.gongchae.bell.alerts;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import kr.gongchae.bell.alerts.AlertMatcher.ActiveSubscription;
import kr.gongchae.bell.ingestion.runtime.CollectionRuns;
import kr.gongchae.bell.ops.Heartbeat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Slot-claimed daily alert batch generators (ADR-0003, ADR-0014):
 * - {@code EMAIL_DIGEST} at >= 08:00 KST (NEW + D-3 combined into one email per subscription)
 * - {@code DISCORD_DEADLINE} at >= 09:00 KST (D-3 only; Discord NEW is created in the regular collection TX)
 *
 * <p>Same-day catch-up runs once per KST date via {@code collect_run(kind, date)} unique claim.
 * The {@link Heartbeat} digest ping is emitted only by the app that wins and completes the DIGEST slot.
 */
@Component
public class AlertBatchGenerator {
    static final ZoneId KST = ZoneId.of("Asia/Seoul");
    static final LocalTime EMAIL_DIGEST_TIME = LocalTime.of(8, 0);
    static final LocalTime DISCORD_DEADLINE_TIME = LocalTime.of(9, 0);
    private static final Logger log = LoggerFactory.getLogger(AlertBatchGenerator.class);

    private final CollectionRuns runs;
    private final AlertMatcher matcher;
    private final AlertOutbox outbox;
    private final AlertsProperties properties;
    private final Heartbeat digestHeartbeat;
    private final TransactionTemplate tx;
    private final Clock clock;

    public AlertBatchGenerator(CollectionRuns runs, AlertMatcher matcher, AlertOutbox outbox,
            AlertsProperties properties, @Qualifier("digestHeartbeat") Heartbeat digestHeartbeat,
            PlatformTransactionManager manager, Clock clock) {
        this.runs = runs;
        this.matcher = matcher;
        this.outbox = outbox;
        this.properties = properties;
        this.digestHeartbeat = digestHeartbeat;
        this.tx = new TransactionTemplate(manager);
        this.clock = clock;
    }

    /** Catch-up entry point for EMAIL_DIGEST (>= 08:00 KST on the current KST day). */
    public boolean runDigestIfDue(Instant instant) {
        if (!properties.enabled()) return false;
        ZonedDateTime kst = instant.atZone(KST);
        if (kst.toLocalTime().isBefore(EMAIL_DIGEST_TIME)) return false;
        return generateEmailDigest(kst.toLocalDate(), instant) >= 0;
    }

    /** Catch-up entry point for DISCORD_DEADLINE (>= 09:00 KST on the current KST day). */
    public boolean runDeadlineIfDue(Instant instant) {
        if (!properties.enabled()) return false;
        ZonedDateTime kst = instant.atZone(KST);
        if (kst.toLocalTime().isBefore(DISCORD_DEADLINE_TIME)) return false;
        return generateDiscordDeadline(kst.toLocalDate(), instant) >= 0;
    }

    /** Generates EMAIL_DIGEST jobs for {@code batchDate} if this app wins the DIGEST slot; returns -1 if skipped. */
    public int generateEmailDigest(LocalDate batchDate) {
        return generateEmailDigest(batchDate, clock.instant());
    }

    int generateEmailDigest(LocalDate batchDate, Instant instant) {
        if (!properties.enabled()) return -1;
        var run = runs.claim("DIGEST", batchDate.toString());
        if (run.isEmpty()) return -1;

        long runId = run.get();
        try {
            OffsetDateTime now = OffsetDateTime.ofInstant(instant, ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
            Integer created = tx.execute(status -> {
                int jobs = 0;
                for (ActiveSubscription sub : matcher.findActiveSubscribers("EMAIL", true, true)) {
                    List<Long> deadlineSns = sub.conditions().notifyDeadline()
                            ? matcher.findMatchingDeadline(sub.id(), sub.conditions(), batchDate) : List.of();
                    List<Long> newSns = sub.conditions().notifyNew()
                            ? matcher.findMatchingNew(sub.id(), sub.conditions(), sub.confirmedAt(), batchDate, null)
                            : List.of();
                    if (outbox.createJobWithItems(sub.id(), "EMAIL_DIGEST", batchDate.toString(), now, deadlineSns, newSns)) {
                        jobs++;
                    }
                }
                return jobs;
            });
            int total = created == null ? 0 : created;
            runs.finish(runId, "COMPLETE", null, total, total, total, total);
            digestHeartbeat.ping();
            return total;
        } catch (RuntimeException failure) {
            runs.finish(runId, "FAILED", "DIGEST_FAILED", null, null, null);
            log.warn("ALERT_DIGEST_FAILED {}", failure.getClass().getSimpleName());
            throw failure;
        }
    }

    /** Generates DISCORD_DEADLINE jobs for {@code batchDate} if this app wins the DEADLINE slot; returns -1 if skipped. */
    public int generateDiscordDeadline(LocalDate batchDate) {
        return generateDiscordDeadline(batchDate, clock.instant());
    }

    int generateDiscordDeadline(LocalDate batchDate, Instant instant) {
        if (!properties.enabled()) return -1;
        var run = runs.claim("DEADLINE", batchDate.toString());
        if (run.isEmpty()) return -1;

        long runId = run.get();
        try {
            OffsetDateTime now = OffsetDateTime.ofInstant(instant, ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
            Integer created = tx.execute(status -> {
                int jobs = 0;
                for (ActiveSubscription sub : matcher.findActiveSubscribers("DISCORD", false, true)) {
                    List<Long> deadlineSns = matcher.findMatchingDeadline(sub.id(), sub.conditions(), batchDate);
                    if (outbox.createJobWithItems(sub.id(), "DISCORD_DEADLINE", batchDate.toString(), now, deadlineSns, List.of())) {
                        jobs++;
                    }
                }
                return jobs;
            });
            int total = created == null ? 0 : created;
            runs.finish(runId, "COMPLETE", null, total, total, total, total);
            return total;
        } catch (RuntimeException failure) {
            runs.finish(runId, "FAILED", "DEADLINE_FAILED", null, null, null);
            log.warn("ALERT_DEADLINE_FAILED {}", failure.getClass().getSimpleName());
            throw failure;
        }
    }
}
