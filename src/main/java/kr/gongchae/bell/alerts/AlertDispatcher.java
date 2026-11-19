package kr.gongchae.bell.alerts;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import kr.gongchae.bell.alerts.AlertMatcher.ActiveSubscription;
import kr.gongchae.bell.alerts.AlertMatcher.PostingSummary;
import kr.gongchae.bell.alerts.DeliveryLedger.AsyncJob;
import kr.gongchae.bell.alerts.DeliveryLedger.Claim;
import kr.gongchae.bell.alerts.DeliveryLedger.Reservation;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Asynchronous alert job dispatcher (TX2 + TX3, contract 12 §2-§5, 13 §2-§3, 14 §2-§4, 03-codex).
 *
 * <p>Outer transaction A holds {@code subscription FOR SHARE} and computes current eligibility and payload hash in
 * memory without any lock or DML on notification/item before calling R on connection B ({@code REQUIRES_NEW}).
 * After R commits, A takes {@code notification FOR UPDATE}, re-checks 23 h lifetime and KST eligibility (including
 * midnight D-3 transitions), performs at most 1 HTTP call (10 s bound), records the outcome, and transitions the job.
 * Discord 404 disables the subscription in a separate short TX3 after A commits (no lock escalation).
 */
@Component
public class AlertDispatcher {
    static final ZoneId KST = ZoneId.of("Asia/Seoul");
    static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration[] BACKOFF = {Duration.ofSeconds(2), Duration.ofSeconds(4)};

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final DeliveryLedger ledger;
    private final AlertMatcher matcher;
    private final AlertPayloadBuilder payloads;
    private final RateLimiter limits;
    private final AlertsCrypto crypto;
    private final MailTransport mail;
    private final WebhookTransport webhooks;
    private final AlertsMetrics metrics;
    private final Clock clock;

    public AlertDispatcher(JdbcClient jdbc, PlatformTransactionManager manager, DeliveryLedger ledger,
            AlertMatcher matcher, AlertPayloadBuilder payloads, RateLimiter limits, AlertsCrypto crypto,
            MailTransport mail, WebhookTransport webhooks, AlertsMetrics metrics, Clock clock) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(manager);
        this.ledger = ledger;
        this.matcher = matcher;
        this.payloads = payloads;
        this.limits = limits;
        this.crypto = crypto;
        this.mail = mail;
        this.webhooks = webhooks;
        this.metrics = metrics;
        this.clock = clock;
    }

    public void dispatchClaim(Claim claim) {
        UUID disableDiscordSubId = tx.execute(status -> dispatchInTransaction(claim));
        if (disableDiscordSubId != null) {
            disableWebhook404(disableDiscordSubId);
        }
    }

    private UUID dispatchInTransaction(Claim claim) {
        var optJob = ledger.loadAsyncJob(claim.id());
        if (optJob.isEmpty()) return null;
        AsyncJob job = optJob.get();
        if (!DeliveryLedger.ASYNC_KINDS.contains(job.kind())) {
            throw new IllegalStateException("SYNC_KIND_IN_ASYNC_DISPATCH");
        }

        // Lock parent subscription FOR SHARE first (parent -> child order, before any notification/item lock or R).
        var optSub = matcher.lockForShare(job.subscriptionId());
        if (optSub.isEmpty()) return null;
        ActiveSubscription sub = optSub.get();
        if (!"ACTIVE".equals(sub.status())) {
            if (ledger.lockClaim(claim)) ledger.finishAsync(claim, false, "NOT_ACTIVE", true);
            return null;
        }

        OffsetDateTime now = now();
        if (!now.isBefore(job.createdAt().plus(DeliveryLedger.LIFETIME))) {
            if (ledger.lockClaim(claim)) ledger.finishAsync(claim, false, "EXPIRED", false);
            return null;
        }

        RecordedItems recorded = loadRecordedItems(job.id());
        LocalDate kstToday = now.atZoneSameInstant(KST).toLocalDate();
        EligibleSet eligible = evaluateEligible(sub, recorded, kstToday);

        if (job.anyUnknown()) {
            if (eligible.hasDropped() || eligible.isEmpty()) {
                if (ledger.lockClaim(claim)) ledger.finishAsync(claim, false, "TARGET_CHANGED", false);
                return null;
            }
        } else if (eligible.isEmpty()) {
            if (ledger.lockClaim(claim)) ledger.finishAsync(claim, false, "NO_ITEMS", true);
            return null;
        }

        byte[] payload = payloads.build(job.kind(), job.batchKey(), sub, eligible.deadline(), eligible.newPostings());
        byte[] hash = AlertsCrypto.sha256(payload);
        if (job.anyUnknown() && !Arrays.equals(job.payloadSha256(), hash)) {
            if (ledger.lockClaim(claim)) ledger.finishAsync(claim, false, "BODY_CHANGED", false);
            return null;
        }

        UUID key = job.allDefinite() ? UUID.randomUUID() : job.idempotencyKey();
        boolean isEmail = "EMAIL_DIGEST".equals(job.kind());
        byte[] totalKey = crypto.limitKey("SEND_TOTAL", isEmail ? "email" : "discord");
        RateLimiter.Limit totalLimit = isEmail ? RateLimiter.EMAIL_TOTAL : RateLimiter.DISCORD_TOTAL;

        // R (connection B): must run before any lock or DML on notification/item in A.
        Reservation reservation = ledger.reserve(claim, key, hash, totalKey, totalLimit,
                () -> limits.poolWait().plus(Duration.ofSeconds(5)));

        if (reservation instanceof Reservation.Fenced) return null;
        if (reservation instanceof Reservation.Capped) {
            if (ledger.lockClaim(claim)) {
                OffsetDateTime nextWin = limits.nextWindow(totalLimit.window()).atOffset(ZoneOffset.UTC)
                        .truncatedTo(ChronoUnit.MICROS);
                if (!ledger.retry(claim, nextWin, "SEND_CAP")) {
                    ledger.finishAsync(claim, false, "EXPIRED", false);
                }
            }
            return null;
        }

        Reservation.Reserved reserved = (Reservation.Reserved) reservation;
        if (!ledger.lockReserved(claim, reserved.attemptNo(), key)) return null;

        OffsetDateTime nowAfterR = now();
        if (!nowAfterR.isBefore(job.createdAt().plus(DeliveryLedger.LIFETIME))) {
            ledger.recordOutcome(reserved.attemptId(), SendOutcome.Result.of(SendOutcome.NOT_SENT));
            ledger.finishAsync(claim, false, "EXPIRED", false);
            return null;
        }

        // Post-R recheck (catches midnight D-3 drop or concurrent posting changes without refunding R or calling HTTP).
        LocalDate kstAfterR = nowAfterR.atZoneSameInstant(KST).toLocalDate();
        EligibleSet rechecked = evaluateEligible(sub, recorded, kstAfterR);
        byte[] recheckedPayload = rechecked.isEmpty() ? new byte[0]
                : payloads.build(job.kind(), job.batchKey(), sub, rechecked.deadline(), rechecked.newPostings());
        byte[] recheckedHash = rechecked.isEmpty() ? new byte[0] : AlertsCrypto.sha256(recheckedPayload);

        if (!eligible.sameSns(rechecked) || !Arrays.equals(hash, recheckedHash)) {
            ledger.recordOutcome(reserved.attemptId(), SendOutcome.Result.of(SendOutcome.NOT_SENT));
            metrics.recordAttemptOutcome(job.kind(), SendOutcome.NOT_SENT);
            if (job.anyUnknown()) {
                ledger.finishAsync(claim, false,
                        eligible.sameSns(rechecked) ? "BODY_CHANGED" : "TARGET_CHANGED", false);
            } else if (rechecked.isEmpty()) {
                ledger.finishAsync(claim, false, "NO_ITEMS", true);
            } else {
                deleteDroppedItems(sub.id(), job.id(), rechecked);
                if (reserved.attemptNo() >= DeliveryLedger.MAX_ATTEMPTS
                        || !ledger.retry(claim, nowAfterR, "NOT_SENT")) {
                    ledger.finishAsync(claim, false,
                            reserved.attemptNo() >= DeliveryLedger.MAX_ATTEMPTS ? "ATTEMPTS_EXHAUSTED" : "EXPIRED",
                            false);
                }
            }
            return null;
        }

        if (!job.anyUnknown() && eligible.hasDropped()) {
            deleteDroppedItems(sub.id(), job.id(), eligible);
        }

        SendOutcome.Result result = sendProvider(isEmail, sub, payload, key);
        ledger.recordOutcome(reserved.attemptId(), result);
        metrics.recordAttemptOutcome(job.kind(), result.outcome());

        SendOutcome outcome = result.outcome();
        if (outcome == SendOutcome.SUCCESS) {
            OffsetDateTime finishedAt = now();
            if (ledger.finishAsync(claim, true, null, false)) {
                recordSuccessAfterCommit(job, rechecked.newPostings(), finishedAt);
            }
            return null;
        }

        if (outcome.kind == SendOutcome.Kind.STOP) {
            ledger.finishAsync(claim, false, outcome.name(), false);
            return outcome == SendOutcome.WEBHOOK_404 ? sub.id() : null;
        }

        if (outcome.kind == SendOutcome.Kind.NEXT_WINDOW) {
            OffsetDateTime nextUtcDay = limits.nextWindow(RateLimiter.EMAIL_TOTAL.window())
                    .atOffset(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
            if (reserved.attemptNo() >= DeliveryLedger.MAX_ATTEMPTS
                    || !ledger.retry(claim, nextUtcDay, "QUOTA_DAILY")) {
                ledger.finishAsync(claim, false,
                        reserved.attemptNo() >= DeliveryLedger.MAX_ATTEMPTS ? "ATTEMPTS_EXHAUSTED" : "EXPIRED", false);
            }
            return null;
        }

        // RETRY or UNKNOWN
        if (reserved.attemptNo() >= DeliveryLedger.MAX_ATTEMPTS) {
            ledger.finishAsync(claim, false, "ATTEMPTS_EXHAUSTED", false);
            return null;
        }

        Duration baseWait = BACKOFF[Math.min(reserved.attemptNo() - 1, BACKOFF.length - 1)];
        Duration wait = (outcome == SendOutcome.RATE_LIMITED && result.retryAfter() != null
                && result.retryAfter().compareTo(baseWait) > 0) ? result.retryAfter() : baseWait;
        OffsetDateTime nextAt = now().plus(wait);
        if (!ledger.retry(claim, nextAt, outcome.name())) {
            ledger.finishAsync(claim, false, "EXPIRED", false);
        }
        return null;
    }

    private void disableWebhook404(UUID subscriptionId) {
        tx.executeWithoutResult(status -> jdbc.sql("""
                UPDATE subscription SET status = 'DISABLED', disabled_at = :now, disabled_reason = 'WEBHOOK_404', updated_at = :now
                WHERE id = :id AND status = 'ACTIVE'
                """).param("now", now()).param("id", subscriptionId).update());
    }

    private SendOutcome.Result sendProvider(boolean isEmail, ActiveSubscription sub, byte[] payload, UUID key) {
        if (isEmail) {
            return mail.send(payload, key, HTTP_TIMEOUT);
        }
        String raw = crypto.decryptWebhook(sub.id(), sub.webhookEnc(), sub.webhookKeyId());
        WebhookUrl url = WebhookUrl.parse(raw).orElseThrow(() -> new IllegalStateException("WEBHOOK_URL_INVALID"));
        return webhooks.send(url, payload, HTTP_TIMEOUT);
    }

    private void deleteDroppedItems(UUID subscriptionId, long notificationId, EligibleSet kept) {
        ledger.deleteItems(subscriptionId, notificationId, "DEADLINE", kept.droppedDeadline());
        ledger.deleteItems(subscriptionId, notificationId, "NEW", kept.droppedNew());
    }

    private RecordedItems loadRecordedItems(long notificationId) {
        List<Long> deadline = new ArrayList<>();
        List<Long> newSns = new ArrayList<>();
        jdbc.sql("SELECT sn, kind FROM notification_item WHERE notification_id = :id ORDER BY kind, sn")
                .param("id", notificationId)
                .query(rs -> {
                    long sn = rs.getLong(1);
                    if ("DEADLINE".equals(rs.getString(2))) deadline.add(sn);
                    else newSns.add(sn);
                });
        return new RecordedItems(deadline, newSns);
    }

    private EligibleSet evaluateEligible(ActiveSubscription sub, RecordedItems recorded, LocalDate kstToday) {
        List<PostingSummary> eligibleDeadline = matcher.findStillEligible(
                sub.conditions(), sub.confirmedAt(), kstToday, "DEADLINE", recorded.deadlineSns());
        List<PostingSummary> eligibleNew = matcher.findStillEligible(
                sub.conditions(), sub.confirmedAt(), kstToday, "NEW", recorded.newSns());
        Set<Long> keptDeadline = eligibleDeadline.stream().map(PostingSummary::sn).collect(Collectors.toSet());
        Set<Long> keptNew = eligibleNew.stream().map(PostingSummary::sn).collect(Collectors.toSet());
        List<Long> droppedDeadline = recorded.deadlineSns().stream().filter(sn -> !keptDeadline.contains(sn)).toList();
        List<Long> droppedNew = recorded.newSns().stream().filter(sn -> !keptNew.contains(sn)).toList();
        return new EligibleSet(eligibleDeadline, eligibleNew, droppedDeadline, droppedNew);
    }

    private void recordSuccessAfterCommit(AsyncJob job, List<PostingSummary> newPostings, OffsetDateTime finishedAt) {
        List<PostingSummary> snapshot = List.copyOf(newPostings);
        Runnable record = () -> {
            metrics.recordDeliveryLatency(job.kind(), job.createdAt(), finishedAt);
            for (PostingSummary item : snapshot) {
                if (item.firstSeenAt() != null) {
                    metrics.recordCollectionToDeliveryLatency(job.kind(), item.firstSeenAt(), finishedAt);
                }
            }
            if (job.anyUnknown()) metrics.recordDuplicateRisk(job.kind());
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    record.run();
                }
            });
        } else {
            record.run();
        }
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
    }

    private record RecordedItems(List<Long> deadlineSns, List<Long> newSns) {}

    private record EligibleSet(List<PostingSummary> deadline, List<PostingSummary> newPostings,
            List<Long> droppedDeadline, List<Long> droppedNew) {
        boolean isEmpty() { return deadline.isEmpty() && newPostings.isEmpty(); }
        boolean hasDropped() { return !droppedDeadline.isEmpty() || !droppedNew.isEmpty(); }
        boolean sameSns(EligibleSet other) {
            return sns(deadline).equals(sns(other.deadline)) && sns(newPostings).equals(sns(other.newPostings));
        }
        private static List<Long> sns(List<PostingSummary> list) {
            return list.stream().map(PostingSummary::sn).toList();
        }
    }
}
