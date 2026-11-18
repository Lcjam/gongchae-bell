package kr.gongchae.bell.alerts;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import kr.gongchae.bell.ingestion.PostingIngestor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Same-transaction transactional outbox for regular-collection NEW Discord alerts (#7, FR-A4) and shared batch
 * notification/item writer used by both {@link PostingIngestor} and daily batch generators.
 */
@Component
public class AlertOutbox implements PostingIngestor.RegularNewOutbox {
    static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final JdbcClient jdbc;
    private final AlertsProperties properties;
    private final AlertMatcher matcher;

    public AlertOutbox(JdbcClient jdbc, AlertsProperties properties, AlertMatcher matcher) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.matcher = matcher;
    }

    @Override
    public void recordRegularNew(long runId, List<Long> openNewSns, Instant observedAt) {
        if (!properties.enabled() || openNewSns.isEmpty()) return;
        String slot = jdbc.sql("SELECT slot FROM collect_run WHERE id = :id").param("id", runId)
                .query(String.class).single();
        LocalDate kstToday = observedAt.atZone(KST).toLocalDate();
        OffsetDateTime now = OffsetDateTime.ofInstant(observedAt, ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        for (AlertMatcher.ActiveSubscription sub : matcher.findActiveSubscribers("DISCORD", true, false)) {
            List<Long> matched = matcher.findMatchingNew(sub.id(), sub.conditions(), sub.confirmedAt(), kstToday, openNewSns);
            if (!matched.isEmpty()) {
                createJobWithItems(sub.id(), "DISCORD_NEW", slot, now, List.of(), matched);
            }
        }
    }

    /** Creates an async notification job and claims its (subscription_id, sn, kind) items idempotently. */
    boolean createJobWithItems(UUID subscriptionId, String kind, String batchKey, OffsetDateTime now,
            List<Long> deadlineSns, List<Long> newSns) {
        if (deadlineSns.isEmpty() && newSns.isEmpty()) return false;
        // Lock parent FOR KEY SHARE before child INSERT (#6 contract §3) to serialize with concurrent unsubscribe
        // (FOR UPDATE + DELETE): if the subscription is deleted or disabled concurrently, skip it cleanly without FK 23503.
        boolean stillActive = jdbc.sql("""
                SELECT 1 FROM subscription WHERE id = :subId AND status = 'ACTIVE' FOR KEY SHARE
                """).param("subId", subscriptionId).query(Integer.class).optional().isPresent();
        if (!stillActive) return false;

        Optional<Long> notifId = jdbc.sql("""
                INSERT INTO notification(subscription_id, kind, batch_key, status, next_attempt_at, created_at, idempotency_key)
                VALUES (:subId, :kind, :batchKey, 'PENDING', :now, :now, :key)
                ON CONFLICT (subscription_id, kind, batch_key) DO NOTHING
                RETURNING id
                """).param("subId", subscriptionId).param("kind", kind).param("batchKey", batchKey).param("now", now)
                .param("key", UUID.randomUUID()).query(Long.class).optional();
        if (notifId.isEmpty()) return false;

        int inserted = 0;
        for (Long sn : deadlineSns) {
            inserted += jdbc.sql("""
                    INSERT INTO notification_item(subscription_id, sn, kind, notification_id)
                    VALUES (:subId, :sn, 'DEADLINE', :notifId)
                    ON CONFLICT (subscription_id, sn, kind) DO NOTHING
                    """).param("subId", subscriptionId).param("sn", sn).param("notifId", notifId.get()).update();
        }
        for (Long sn : newSns) {
            inserted += jdbc.sql("""
                    INSERT INTO notification_item(subscription_id, sn, kind, notification_id)
                    VALUES (:subId, :sn, 'NEW', :notifId)
                    ON CONFLICT (subscription_id, sn, kind) DO NOTHING
                    """).param("subId", subscriptionId).param("sn", sn).param("notifId", notifId.get()).update();
        }
        if (inserted == 0) {
            jdbc.sql("DELETE FROM notification WHERE id = :id").param("id", notifId.get()).update();
            return false;
        }
        return true;
    }
}
