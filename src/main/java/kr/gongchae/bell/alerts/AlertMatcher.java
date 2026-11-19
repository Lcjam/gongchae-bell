package kr.gongchae.bell.alerts;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import kr.gongchae.bell.catalog.CatalogRepository;
import kr.gongchae.bell.catalog.JobSearchCriteria;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Shared subscription condition matching and current-eligibility queries (#7, 03-codex-alert-eligibility-decision).
 * Reuses {@link CatalogRepository#appendConditions} so web filters and alert matching always evaluate identically.
 */
@Component
public class AlertMatcher {
    public record ActiveSubscription(UUID id, String channel, String status, String email, byte[] webhookEnc,
            String webhookKeyId, SubscriptionService.Conditions conditions, int tokenVersion,
            OffsetDateTime confirmedAt) {}

    public record PostingSummary(long sn, String instName, String title, LocalDate endDate, OffsetDateTime firstSeenAt) {
        public PostingSummary(long sn, String instName, String title, LocalDate endDate) {
            this(sn, instName, title, endDate, null);
        }
    }

    private final JdbcClient jdbc;

    public AlertMatcher(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public static JobSearchCriteria toCriteria(SubscriptionService.Conditions conditions) {
        return new JobSearchCriteria(conditions.ncs(), conditions.region(), conditions.hire(),
                conditions.career(), "open", conditions.q(), "deadline", 1);
    }

    /** Locks the subscription FOR SHARE before any R reservation or notification lock (contract 13-3, 14-3). */
    public Optional<ActiveSubscription> lockForShare(UUID subscriptionId) {
        return jdbc.sql("""
                SELECT id, channel, status, email, webhook_enc, webhook_key_id, conditions::text,
                       notify_new, notify_deadline, token_version, confirmed_at
                FROM subscription WHERE id = :id FOR SHARE
                """).param("id", subscriptionId)
                .query((rs, row) -> new ActiveSubscription(
                        rs.getObject(1, UUID.class),
                        rs.getString(2),
                        rs.getString(3),
                        rs.getString(4),
                        rs.getBytes(5),
                        rs.getString(6),
                        SubscriptionService.conditionsOf(rs.getString(7), rs.getBoolean(8), rs.getBoolean(9)),
                        rs.getInt(10),
                        rs.getObject(11, OffsetDateTime.class)))
                .optional();
    }

    public List<ActiveSubscription> findActiveSubscribers(String channel, boolean requireNew, boolean requireDeadline) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, channel, status, email, webhook_enc, webhook_key_id, conditions::text,
                       notify_new, notify_deadline, token_version, confirmed_at
                FROM subscription
                WHERE channel = :channel AND status = 'ACTIVE'
                """);
        if (requireNew && requireDeadline) sql.append(" AND (notify_new OR notify_deadline)");
        else if (requireNew) sql.append(" AND notify_new");
        else if (requireDeadline) sql.append(" AND notify_deadline");
        sql.append(" ORDER BY id");
        return jdbc.sql(sql.toString()).param("channel", channel)
                .query((rs, row) -> new ActiveSubscription(
                        rs.getObject(1, UUID.class),
                        rs.getString(2),
                        rs.getString(3),
                        rs.getString(4),
                        rs.getBytes(5),
                        rs.getString(6),
                        SubscriptionService.conditionsOf(rs.getString(7), rs.getBoolean(8), rs.getBoolean(9)),
                        rs.getInt(10),
                        rs.getObject(11, OffsetDateTime.class)))
                .list();
    }

    /** Matches OPEN, non-backfill postings first seen on/after subscription confirmation and not yet claimed for NEW. */
    public List<Long> findMatchingNew(UUID subscriptionId, SubscriptionService.Conditions conditions,
            OffsetDateTime confirmedAt, LocalDate kstToday, List<Long> candidateSnsOrNull) {
        if (!conditions.notifyNew() || confirmedAt == null) return List.of();
        if (candidateSnsOrNull != null && candidateSnsOrNull.isEmpty()) return List.of();

        StringBuilder where = new StringBuilder(" WHERE p.status = 'OPEN' AND p.end_date >= :kstToday"
                + " AND p.from_backfill = false AND p.first_seen_at >= :confirmedAt"
                + " AND NOT EXISTS (SELECT 1 FROM notification_item ni"
                + " WHERE ni.subscription_id = :subId AND ni.sn = p.sn AND ni.kind = 'NEW')");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("kstToday", kstToday);
        params.put("confirmedAt", confirmedAt);
        params.put("subId", subscriptionId);
        if (candidateSnsOrNull != null) {
            where.append(" AND p.sn IN (:sns)");
            params.put("sns", candidateSnsOrNull);
        }
        CatalogRepository.appendConditions(toCriteria(conditions), where, params);
        return jdbc.sql("SELECT p.sn FROM posting p" + where + " ORDER BY p.end_date ASC, p.sn ASC")
                .params(params).query(Long.class).list();
    }

    /** Matches OPEN postings expiring in exactly 3 KST days (end_date = kstToday + 3) and not yet claimed for DEADLINE. */
    public List<Long> findMatchingDeadline(UUID subscriptionId, SubscriptionService.Conditions conditions,
            LocalDate kstToday) {
        if (!conditions.notifyDeadline()) return List.of();

        StringBuilder where = new StringBuilder(" WHERE p.status = 'OPEN' AND p.end_date = :deadlineDate"
                + " AND NOT EXISTS (SELECT 1 FROM notification_item ni"
                + " WHERE ni.subscription_id = :subId AND ni.sn = p.sn AND ni.kind = 'DEADLINE')");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("deadlineDate", kstToday.plusDays(3));
        params.put("subId", subscriptionId);
        CatalogRepository.appendConditions(toCriteria(conditions), where, params);
        return jdbc.sql("SELECT p.sn FROM posting p" + where + " ORDER BY p.end_date ASC, p.sn ASC")
                .params(params).query(Long.class).list();
    }

    /** Re-evaluates which recorded items for a notification are still eligible at {@code kstToday} (before and after R). */
    public List<PostingSummary> findStillEligible(SubscriptionService.Conditions conditions,
            OffsetDateTime confirmedAt, LocalDate kstToday, String itemKind, List<Long> itemSns) {
        if (itemSns.isEmpty()) return List.of();
        if ("NEW".equals(itemKind) && (!conditions.notifyNew() || confirmedAt == null)) return List.of();
        if ("DEADLINE".equals(itemKind) && !conditions.notifyDeadline()) return List.of();

        StringBuilder where = new StringBuilder(" WHERE p.sn IN (:sns) AND p.status = 'OPEN' AND p.end_date >= :kstToday");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("sns", itemSns);
        params.put("kstToday", kstToday);
        if ("NEW".equals(itemKind)) {
            where.append(" AND p.from_backfill = false AND p.first_seen_at >= :confirmedAt");
            params.put("confirmedAt", confirmedAt);
        } else if ("DEADLINE".equals(itemKind)) {
            where.append(" AND p.end_date = :deadlineDate");
            params.put("deadlineDate", kstToday.plusDays(3));
        } else {
            throw new IllegalArgumentException("UNKNOWN_ITEM_KIND");
        }
        CatalogRepository.appendConditions(toCriteria(conditions), where, params);
        return jdbc.sql("""
                SELECT p.sn, i.name, p.title, p.end_date, p.first_seen_at
                FROM posting p
                JOIN institution i ON i.code = p.inst_code
                """ + where + " ORDER BY p.end_date ASC, p.sn ASC")
                .params(params)
                .query((rs, row) -> new PostingSummary(rs.getLong(1), rs.getString(2), rs.getString(3),
                        rs.getObject(4, LocalDate.class), rs.getObject(5, OffsetDateTime.class)))
                .list();
    }
}
