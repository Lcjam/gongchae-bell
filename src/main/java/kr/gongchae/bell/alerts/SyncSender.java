package kr.gongchae.bell.alerts;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * In-request sends (contract 12 §6, 14-4): confirmation and management-link mails, and the Discord registration
 * test. One budget of {@link #DEADLINE} from the request start, on a monotonic timer, covers every step of the request.
 * <ul>
 * <li>Each statement that can wait on a lock (or run long) gets {@code lock_timeout}/{@code statement_timeout} cut to
 *     what is left minus the shares later steps still need, measured again after each connection is taken.
 *     Nothing left means the step does not start.</li>
 * <li>A new attempt (R) needs {@link #MIN_START} left. R itself keeps {@link #MIN_HTTP} for the request. The HTTP
 *     timeout is measured right before the request and is at most {@link #HTTP_MAX}.</li>
 * <li>The final write gets the {@link #CLEANUP} share. When a bounded wait runs out, the job ends as DEADLINE within
 *     that share, and an attempt already reserved stays unknown, as the contract requires.</li>
 * </ul>
 * Not covered by the budget: a connection-pool wait (Hikari {@code connection-timeout}, 5 s per connection; the
 * budget is only re-checked after it), a GC or OS stall, and a database that stops answering. In those cases the
 * response can run past 25 s.
 */
@Component
public class SyncSender {
    public static final Duration DEADLINE = Duration.ofSeconds(25);
    static final Duration MIN_START = Duration.ofSeconds(5);
    static final Duration CLEANUP = Duration.ofSeconds(2);
    static final Duration HTTP_MAX = Duration.ofSeconds(10);
    static final Duration MIN_HTTP = Duration.ofSeconds(1);
    private static final Duration[] BACKOFF = {Duration.ofSeconds(2), Duration.ofSeconds(4)};

    /** Monotonic time and sleeping, replaceable in tests. */
    public interface Timer {
        long nanoTime();
        void sleep(Duration duration) throws InterruptedException;
    }

    /**
     * A row-backed mail. {@link #stillEligible} runs in A: it takes the subscription row FOR SHARE (parent first)
     * and checks that the token or capability this mail carries is still the current one.
     */
    public interface MailJob {
        boolean stillEligible(JdbcClient jdbc);
        String cancelReason();
        byte[] payload();
    }

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final DeliveryLedger ledger;
    private final RateLimiter limits;
    private final MailTransport mail;
    private final WebhookTransport webhooks;
    private final AlertsCrypto crypto;
    private final Timer timer;

    public SyncSender(JdbcClient jdbc, PlatformTransactionManager manager, DeliveryLedger ledger, RateLimiter limits,
            MailTransport mail, WebhookTransport webhooks, AlertsCrypto crypto, Timer timer) {
        this.jdbc = jdbc; this.tx = new TransactionTemplate(manager); this.ledger = ledger; this.limits = limits;
        this.mail = mail; this.webhooks = webhooks; this.crypto = crypto; this.timer = timer;
    }

    public long start() { return timer.nanoTime(); }

    private Duration left(long start) { return DEADLINE.minusNanos(timer.nanoTime() - start); }

    /** What a step before the send may still wait: the rest of the budget minus the final write's share. */
    public Duration waitLimit(long start) { return left(start).minus(CLEANUP); }

    private enum Step { DONE, NEXT }
    private record Round(Step step, Duration backoff) {}
    private static final Round DONE = new Round(Step.DONE, null);

    /** Delivers one synchronous mail job already claimed by this request; the job row records the outcome. */
    public void deliverMail(DeliveryLedger.Claim claim, MailJob job, long start) {
        byte[] payload = job.payload();
        byte[] hash = AlertsCrypto.sha256(payload);
        byte[] totalKey = crypto.limitKey("SEND_TOTAL", "email");
        for (int round = 0; ; round++) {
            if (left(start).compareTo(MIN_START) < 0) { end(claim, "DEADLINE"); return; }  // a sleep ran long
            int current = round;
            Round next;
            try {
                next = tx.execute(status -> attempt(claim, job, payload, hash, totalKey, start, current));
            } catch (CannotAcquireLockException | QueryTimeoutException outOfTime) {
                end(claim, "DEADLINE");  // A rolled back; an R already committed stays (its NULL attempt is unknown)
                return;
            }
            if (next == null || next.step() == Step.DONE) return;
            if (!sleep(next.backoff())) { end(claim, "INTERRUPTED"); return; }
        }
    }

    /** One round in A. Every stop is written here with A's connection; the share for that write is CLEANUP. */
    private Round attempt(DeliveryLedger.Claim claim, MailJob job, byte[] payload, byte[] hash, byte[] totalKey, long start, int round) {
        // Measured again after the pool handed over the connection (agy M-1): no new attempt under MIN_START.
        if (left(start).compareTo(MIN_START) < 0) return stop(claim, "DEADLINE", false);
        ledger.boundWaits(waitLimit(start));
        if (!job.stillEligible(jdbc)) return stop(claim, job.cancelReason(), true);
        ledger.boundWaits(waitLimit(start));
        DeliveryLedger.History history = ledger.history(claim.id());
        UUID key = history.key();
        if (history.allDefinite()) key = UUID.randomUUID();  // nothing went out: a fresh key cannot duplicate
        else if (history.anyUnknown() && !Arrays.equals(history.payloadHash(), hash)) return stop(claim, "BODY_CHANGED", false);
        if (left(start).compareTo(MIN_START) < 0) return stop(claim, "DEADLINE", false);  // before R: reserve only with time to send
        DeliveryLedger.Reservation reservation = ledger.reserve(claim, key, hash, totalKey, RateLimiter.EMAIL_TOTAL,
                () -> waitLimit(start).minus(MIN_HTTP));
        if (reservation instanceof DeliveryLedger.Reservation.Capped) return stop(claim, "SEND_CAP", false);
        if (!(reservation instanceof DeliveryLedger.Reservation.Reserved reserved)) return DONE;

        boolean roomForRequest = waitLimit(start).compareTo(MIN_HTTP) > 0;
        ledger.boundWaits(roomForRequest ? waitLimit(start).minus(MIN_HTTP) : CLEANUP);
        if (!ledger.lockReserved(claim, reserved.attemptNo(), key)) return DONE;
        String reason = !roomForRequest ? "DEADLINE" : !job.stillEligible(jdbc) ? job.cancelReason() : null;
        Duration timeout = min(HTTP_MAX, waitLimit(start));  // measured right before the request
        if (reason == null && timeout.compareTo(MIN_HTTP) < 0) reason = "DEADLINE";
        if (reason != null) {
            ledger.boundWaits(CLEANUP);
            ledger.recordOutcome(reserved.attemptId(), SendOutcome.Result.of(SendOutcome.NOT_SENT));
            ledger.finish(claim, false, reason, !reason.equals("DEADLINE"));
            return DONE;
        }
        SendOutcome.Result sent = mail.send(payload, key, timeout);
        ledger.boundWaits(CLEANUP);
        ledger.recordOutcome(reserved.attemptId(), sent);
        if (sent.outcome() == SendOutcome.SUCCESS) {
            ledger.finish(claim, true, null, false);
            return DONE;
        }
        // A request cannot wait for the next quota window: quota and permanent errors end here.
        if (sent.outcome().kind == SendOutcome.Kind.STOP || sent.outcome().kind == SendOutcome.Kind.NEXT_WINDOW)
            return finished(claim, sent.outcome().name());
        if (reserved.attemptNo() >= DeliveryLedger.MAX_ATTEMPTS) return finished(claim, "ATTEMPTS_EXHAUSTED");
        Duration wait = BACKOFF[Math.min(round, BACKOFF.length - 1)];
        if (sent.retryAfter() != null && sent.retryAfter().compareTo(wait) > 0) return finished(claim, "RATE_LIMITED");  // 12 §6
        if (left(start).minus(wait).compareTo(MIN_START) < 0) return finished(claim, "DEADLINE");
        return new Round(Step.NEXT, wait);
    }

    private Round stop(DeliveryLedger.Claim claim, String reason, boolean cancel) {
        ledger.boundWaits(CLEANUP);
        ledger.finish(claim, false, reason, cancel);
        return DONE;
    }

    /** After the request: the CLEANUP bound is already set. */
    private Round finished(DeliveryLedger.Claim claim, String reason) {
        ledger.finish(claim, false, reason, false);
        return DONE;
    }

    private void end(DeliveryLedger.Claim claim, String reason) {
        tx.executeWithoutResult(status -> {
            ledger.boundWaits(CLEANUP);
            ledger.finish(claim, false, reason, false);
        });
    }

    /** One attempt of the Discord registration test, kept in memory: there is no row before the test succeeds. */
    public record WebhookAttempt(int attemptNo, SendOutcome.Result result) {}

    /**
     * Registration test (12 §6): no subscription or notification row exists yet, so each attempt is charged only to
     * the Discord hourly SEND_TOTAL. The charge commits before the request, is never refunded and keeps room for the
     * request. Attempts come back for the success-only audit rows.
     */
    public List<WebhookAttempt> testWebhook(WebhookUrl url, byte[] payload, long start) {
        List<WebhookAttempt> attempts = new ArrayList<>();
        byte[] totalKey = crypto.limitKey("SEND_TOTAL", "discord");
        for (int no = 1; no <= DeliveryLedger.MAX_ATTEMPTS; no++) {
            if (left(start).compareTo(MIN_START) < 0) break;
            if (!limits.acquire(RateLimiter.Scope.SEND_TOTAL, totalKey, RateLimiter.DISCORD_TOTAL, () -> waitLimit(start).minus(MIN_HTTP))) break;
            Duration timeout = min(HTTP_MAX, waitLimit(start));  // DNS check and request share it (C5)
            if (timeout.compareTo(MIN_HTTP) < 0) break;
            SendOutcome.Result result = webhooks.send(url, payload, timeout);
            attempts.add(new WebhookAttempt(no, result));
            SendOutcome.Kind kind = result.outcome().kind;
            if (kind == SendOutcome.Kind.SENT || kind == SendOutcome.Kind.STOP || kind == SendOutcome.Kind.NEXT_WINDOW) break;
            if (no == DeliveryLedger.MAX_ATTEMPTS) break;
            Duration wait = BACKOFF[no - 1];
            if (result.retryAfter() != null && result.retryAfter().compareTo(wait) > 0) break;
            if (left(start).minus(wait).compareTo(MIN_START) < 0 || !sleep(wait)) break;
        }
        return attempts;
    }

    private boolean sleep(Duration wait) {
        try { timer.sleep(wait); return true; }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return false; }
    }

    private static Duration min(Duration a, Duration b) { return a.compareTo(b) <= 0 ? a : b; }

    /** Production timer. */
    @Component
    static class SystemTimer implements Timer {
        @Override public long nanoTime() { return System.nanoTime(); }
        @Override public void sleep(Duration duration) throws InterruptedException { Thread.sleep(duration); }
    }
}
