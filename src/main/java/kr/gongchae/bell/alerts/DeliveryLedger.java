package kr.gongchae.bell.alerts;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * notification / notification_attempt bookkeeping (contract 12 §1-§4 with 13/14).
 *
 * <p>Attempt = one committed reservation R: in its own transaction (connection B) R fences the claim
 * ({@code status='SENDING' AND claimed_at=:claim}), raises {@code attempts} below 3, writes key and payload hash,
 * takes one SEND_TOTAL unit and inserts the attempt row with a NULL outcome. Any failure rolls all of it back. Once
 * committed it is never refunded, so provider requests <= attempt rows <= 3. The caller (connection A) must hold no
 * lock or uncommitted write on notification/attempt/item rows while R runs; afterwards it locks the notification row
 * ({@link #lockReserved}) and keeps it through the one HTTP request and its result. Lock order everywhere:
 * subscription, then notification, then attempt/item.
 */
@Component
public class DeliveryLedger {
    public static final List<String> SYNC_KINDS = List.of("EMAIL_CONFIRM", "EMAIL_RECOVER");
    public static final List<String> ASYNC_KINDS = List.of("EMAIL_DIGEST", "DISCORD_NEW", "DISCORD_DEADLINE");
    public static final Duration RECOVER_AFTER = Duration.ofMinutes(5);
    /** Own policy, shorter than Resend's documented 24 h key retention; not a provider guarantee. */
    public static final Duration LIFETIME = Duration.ofHours(23);
    static final int MAX_ATTEMPTS = 3;

    private final JdbcClient jdbc;
    private final Clock clock;
    private final RateLimiter limits;
    private final TransactionTemplate independent;

    public DeliveryLedger(JdbcClient jdbc, Clock clock, RateLimiter limits, PlatformTransactionManager manager) {
        this.jdbc = jdbc; this.clock = clock; this.limits = limits;
        independent = new TransactionTemplate(manager);
        independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** A claimed job: the claim time is the fencing value. */
    public record Claim(long id, OffsetDateTime claimedAt) {}

    public sealed interface Reservation {
        record Reserved(long attemptId, int attemptNo) implements Reservation {}
        /** The claim is gone (finished, swept, recovered or deleted) or three attempts were already reserved. */
        record Fenced() implements Reservation {}
        /** Local SEND_TOTAL ceiling: nothing reserved, no HTTP. */
        record Capped() implements Reservation {}
    }

    /** Key, payload hash and attempt results of a job, read without locks. */
    public record History(UUID key, byte[] payloadHash, int attempts, boolean anyUnknown, boolean allDefinite) {}

    /** A synchronous job row, created SENDING and claimed by the request that creates it (caller's transaction). */
    public Claim createSync(UUID subscriptionId, String kind) {
        if (!SYNC_KINDS.contains(kind)) throw new IllegalArgumentException("NOT_A_SYNC_KIND");
        OffsetDateTime now = now();
        long id = jdbc.sql("""
                INSERT INTO notification(subscription_id, kind, batch_key, status, claimed_at, created_at, idempotency_key)
                VALUES (:subscription, :kind, :batch, 'SENDING', :now, :now, :key) RETURNING id
                """).param("subscription", subscriptionId).param("kind", kind).param("batch", UUID.randomUUID().toString())
                .param("now", now).param("key", UUID.randomUUID()).query(Long.class).single();
        return new Claim(id, now);
    }

    public History history(long id) {
        return jdbc.sql("""
                SELECT n.idempotency_key, n.payload_sha256, n.attempts,
                       EXISTS (SELECT 1 FROM notification_attempt a WHERE a.notification_id = n.id
                               AND (a.outcome IS NULL OR a.outcome IN (:unknown))) AS any_unknown
                FROM notification n WHERE n.id = :id
                """).param("id", id).param("unknown", SendOutcome.UNKNOWN_NAMES)
                .query((rs, row) -> {
                    int attempts = rs.getInt(3);
                    boolean unknown = rs.getBoolean(4);
                    return new History(rs.getObject(1, UUID.class), rs.getBytes(2), attempts, unknown, attempts > 0 && !unknown);
                }).optional().orElseThrow(() -> new IllegalStateException("NOTIFICATION_GONE"));
    }

    /**
     * R: commits on its own connection. {@code total} is the SEND_TOTAL window to charge, or null for none.
     * {@code waitLimit} must hold the pool bound before B is requested, and is read again before each statement:
     * nothing left means nothing is reserved (14-4).
     */
    public Reservation reserve(Claim claim, UUID key, byte[] payloadHash, byte[] totalKey, RateLimiter.Limit total, Supplier<Duration> waitLimit) {
        limits.requirePoolRoom(waitLimit);
        return independent.execute(status -> {
            boundWaits(waitLimit.get());
            var attemptNo = jdbc.sql("""
                    UPDATE notification SET attempts = attempts + 1, idempotency_key = :key, payload_sha256 = :hash
                    WHERE id = :id AND status = 'SENDING' AND claimed_at = :claim AND attempts < :max RETURNING attempts
                    """).param("key", key).param("hash", payloadHash).param("id", claim.id()).param("claim", claim.claimedAt())
                    .param("max", MAX_ATTEMPTS).query(Integer.class).optional();
            if (attemptNo.isEmpty()) return new Reservation.Fenced();
            if (total != null) {
                boundWaits(waitLimit.get());
                if (!limits.acquireHere(RateLimiter.Scope.SEND_TOTAL, totalKey, total.max(), total.window())) {
                    status.setRollbackOnly();
                    return new Reservation.Capped();
                }
            }
            boundWaits(waitLimit.get());
            long attemptId = jdbc.sql("""
                    INSERT INTO notification_attempt(notification_id, attempt_no, started_at) VALUES (:id, :no, :at) RETURNING id
                    """).param("id", claim.id()).param("no", attemptNo.get()).param("at", now()).query(Long.class).single();
            return new Reservation.Reserved(attemptId, attemptNo.get());
        });
    }

    /** In A after R: lock the job and confirm the claim and this reservation still stand. False = send nothing. */
    public boolean lockReserved(Claim claim, int attemptNo, UUID key) {
        return jdbc.sql("""
                SELECT 1 FROM notification WHERE id = :id AND status = 'SENDING' AND claimed_at = :claim
                AND attempts = :no AND idempotency_key = :key FOR UPDATE
                """).param("id", claim.id()).param("claim", claim.claimedAt()).param("no", attemptNo).param("key", key)
                .query(Integer.class).optional().isPresent();
    }

    /** First result wins: a NULL outcome is filled once; a crash marker is never replaced. */
    public void recordOutcome(long attemptId, SendOutcome.Result result) {
        jdbc.sql("""
                UPDATE notification_attempt SET outcome = :outcome, http_status = :status, finished_at = :at
                WHERE id = :id AND outcome IS NULL
                """).param("outcome", result.outcome().name()).param("status", result.httpStatus()).param("at", now())
                .param("id", attemptId).update();
    }

    /**
     * Terminal state (contract 12 §4): SENT on a 2xx; otherwise UNCERTAIN while any attempt may have been sent;
     * otherwise CANCELLED for a "no longer a target" reason and FAILED for the rest. Fenced; false if the claim is gone.
     */
    public boolean finish(Claim claim, boolean sent, String reason, boolean cancel) {
        return jdbc.sql("""
                UPDATE notification n SET status = CASE
                    WHEN :sent THEN 'SENT'
                    WHEN EXISTS (SELECT 1 FROM notification_attempt a WHERE a.notification_id = n.id
                                 AND (a.outcome IS NULL OR a.outcome IN (:unknown))) THEN 'UNCERTAIN'
                    WHEN :cancel THEN 'CANCELLED' ELSE 'FAILED' END,
                  last_error = :reason, finished_at = :now, claimed_at = NULL
                WHERE n.id = :id AND n.status = 'SENDING' AND n.claimed_at = :claim
                """).param("sent", sent).param("unknown", SendOutcome.UNKNOWN_NAMES).param("cancel", cancel)
                .param("reason", reason).param("now", now()).param("id", claim.id()).param("claim", claim.claimedAt()).update() == 1;
    }

    /**
     * Synchronous rows a crashed request left SENDING: a NULL attempt becomes UNKNOWN_CRASH and the row ends (UNCERTAIN
     * if anything may have been sent, else FAILED) with INTERRUPTED. They are never re-sent: the token is gone.
     */
    public int sweepSync() {
        Integer done = independent.execute(status -> {
            List<Long> stale = jdbc.sql("""
                    SELECT id FROM notification WHERE status = 'SENDING' AND kind IN (:kinds) AND claimed_at < :cut
                    ORDER BY id FOR UPDATE SKIP LOCKED
                    """).param("kinds", SYNC_KINDS).param("cut", now().minus(RECOVER_AFTER)).query(Long.class).list();
            for (long id : stale) {
                markCrashed(id);
                jdbc.sql("""
                        UPDATE notification n SET status = CASE WHEN EXISTS (SELECT 1 FROM notification_attempt a
                            WHERE a.notification_id = n.id AND a.outcome IN (:unknown)) THEN 'UNCERTAIN' ELSE 'FAILED' END,
                          last_error = 'INTERRUPTED', finished_at = :now, claimed_at = NULL WHERE n.id = :id
                        """).param("unknown", SendOutcome.UNKNOWN_NAMES).param("now", now()).param("id", id).update();
            }
            return stale.size();
        });
        return done == null ? 0 : done;
    }

    /** #7 foundation: claim due asynchronous jobs (TX1). Synchronous kinds are never selected. */
    public List<Claim> claimDue(int limit) {
        OffsetDateTime now = now();
        return independent.execute(status -> jdbc.sql("""
                UPDATE notification SET status = 'SENDING', claimed_at = :now
                WHERE id IN (SELECT id FROM notification WHERE status IN ('PENDING','RETRY') AND kind IN (:kinds)
                             AND next_attempt_at <= :now ORDER BY next_attempt_at, id LIMIT :limit FOR UPDATE SKIP LOCKED)
                RETURNING id, claimed_at
                """).param("now", now).param("kinds", ASYNC_KINDS).param("limit", limit)
                .query((rs, row) -> new Claim(rs.getLong(1), rs.getObject(2, OffsetDateTime.class))).list());
    }

    /**
     * #7 foundation: asynchronous jobs stuck SENDING past 5 minutes. A NULL attempt becomes UNKNOWN_CRASH,
     * claimed_at is cleared (an old worker's fenced writes then match nothing), and the job goes back to RETRY while
     * attempts and lifetime remain, else it ends by the terminal rule. Synchronous kinds are never touched.
     */
    public int recoverAsync() {
        Integer done = independent.execute(status -> {
            OffsetDateTime now = now();
            List<Long> stale = jdbc.sql("""
                    SELECT id FROM notification WHERE status = 'SENDING' AND kind IN (:kinds) AND claimed_at < :cut
                    ORDER BY id FOR UPDATE SKIP LOCKED
                    """).param("kinds", ASYNC_KINDS).param("cut", now.minus(RECOVER_AFTER)).query(Long.class).list();
            for (long id : stale) {
                markCrashed(id);
                jdbc.sql("""
                        UPDATE notification n SET claimed_at = NULL, status = CASE
                            WHEN n.attempts < :max AND n.created_at + :lifetime * interval '1 second' > :now THEN 'RETRY'
                            WHEN EXISTS (SELECT 1 FROM notification_attempt a WHERE a.notification_id = n.id
                                         AND a.outcome IN (:unknown)) THEN 'UNCERTAIN' ELSE 'FAILED' END,
                          next_attempt_at = :now,
                          finished_at = CASE WHEN n.attempts < :max AND n.created_at + :lifetime * interval '1 second' > :now
                                             THEN NULL ELSE :now END,
                          last_error = CASE WHEN n.attempts < :max AND n.created_at + :lifetime * interval '1 second' > :now
                                            THEN 'UNKNOWN_CRASH'
                                            WHEN n.attempts >= :max THEN 'ATTEMPTS_EXHAUSTED' ELSE 'EXPIRED' END
                        WHERE n.id = :id
                        """).param("max", MAX_ATTEMPTS).param("lifetime", LIFETIME.toSeconds()).param("now", now)
                        .param("unknown", SendOutcome.UNKNOWN_NAMES).param("id", id).update();
            }
            return stale.size();
        });
        return done == null ? 0 : done;
    }

    private void markCrashed(long notificationId) {
        jdbc.sql("""
                UPDATE notification_attempt SET outcome = 'UNKNOWN_CRASH', finished_at = :now
                WHERE notification_id = :id AND outcome IS NULL
                """).param("now", now()).param("id", notificationId).update();
    }

    /** See {@link RateLimiter#boundWaits}: used inside R and the sync loop's A. */
    void boundWaits(Duration limit) { limits.boundWaits(limit); }

    private OffsetDateTime now() { return OffsetDateTime.now(clock).truncatedTo(ChronoUnit.MICROS); }
}
