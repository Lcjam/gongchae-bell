package kr.gongchae.bell.alerts;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.CannotCreateTransactionException;
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
 * Before taking a connection, its configured pool upper bound (currently 5 s) must fit in the remaining step budget.
 * Normal work also keeps that upper bound for a final connection and CLEANUP's 2 s. The cleanup statements share one 2 s
 * deadline, capped by the original request deadline. GC/OS stalls and a database that stops answering can
 * still run past 25 s; ordinary pool and SQL waits are included.
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

    public Duration remaining(long start) { return DEADLINE.minusNanos(timer.nanoTime() - start); }

    /** Keep room for the final write even if A rolls back and needs another connection. */
    public Duration waitLimit(long start) { return remaining(start).minus(limits.poolWait()).minus(CLEANUP); }

    /** One deadline shared by every final-write statement, also capped by the original request deadline. */
    public Supplier<Duration> cleanupWaits(long start) {
        long cleanupStart = timer.nanoTime();
        return () -> min(CLEANUP.minusNanos(timer.nanoTime() - cleanupStart), remaining(start));
    }

    /**
     * Whether an attempt after {@code wait} would still be admitted: MIN_START, and the pool bound for the connection
     * of R (or the Discord count) on top of the request and final shares. Checked before sleeping, so a retry that
     * could not start ends here instead of failing the admission after the sleep.
     */
    private boolean roomForNext(long start, Duration wait) {
        return remaining(start).minus(wait).compareTo(MIN_START) >= 0
                && waitLimit(start).minus(wait).minus(MIN_HTTP).compareTo(limits.poolWait()) >= 0;
    }

    private enum Step { DONE, NEXT }
    private record Round(Step step, Duration backoff) {}
    private static final Round DONE = new Round(Step.DONE, null);

    /** Delivers one synchronous mail job already claimed by this request; the job row records the outcome. */
    public void deliverMail(DeliveryLedger.Claim claim, MailJob job, long start) {
        byte[] payload = job.payload();
        byte[] hash = AlertsCrypto.sha256(payload);
        byte[] totalKey = crypto.limitKey("SEND_TOTAL", "email");
        for (int round = 0; ; round++) {
            if (remaining(start).compareTo(MIN_START) < 0) { end(claim, "DEADLINE", start); return; }  // a sleep ran long
            int current = round;
            Round next;
            try {
                limits.requirePoolRoom(() -> waitLimit(start));
                next = tx.execute(status -> attempt(claim, job, payload, hash, totalKey, start, current));
            } catch (CannotAcquireLockException | QueryTimeoutException | CannotCreateTransactionException outOfTime) {
                // A rolled back (or never got its connection, or R did not get B); an R already committed stays (NULL = unknown)
                end(claim, "DEADLINE", start);
                return;
            }
            if (next == null || next.step() == Step.DONE) return;
            if (!sleep(next.backoff())) { end(claim, "INTERRUPTED", start); return; }
        }
    }

    /** One round in A. Every stop is written here with A's connection; the share for that write is CLEANUP. */
    private Round attempt(DeliveryLedger.Claim claim, MailJob job, byte[] payload, byte[] hash, byte[] totalKey, long start, int round) {
        // Measured again after the pool handed over the connection (agy M-1): no new attempt under MIN_START.
        if (remaining(start).compareTo(MIN_START) < 0) return stop(claim, "DEADLINE", false, start);
        ledger.boundWaits(waitLimit(start));
        if (!job.stillEligible(jdbc)) return stop(claim, job.cancelReason(), true, start);
        ledger.boundWaits(waitLimit(start));
        DeliveryLedger.History history = ledger.history(claim.id());
        UUID key = history.key();
        if (history.allDefinite()) key = UUID.randomUUID();  // nothing went out: a fresh key cannot duplicate
        else if (history.anyUnknown() && !Arrays.equals(history.payloadHash(), hash)) return stop(claim, "BODY_CHANGED", false, start);
        if (remaining(start).compareTo(MIN_START) < 0) return stop(claim, "DEADLINE", false, start);  // before R: reserve only with time to send
        DeliveryLedger.Reservation reservation = ledger.reserve(claim, key, hash, totalKey, RateLimiter.EMAIL_TOTAL,
                () -> waitLimit(start).minus(MIN_HTTP));
        if (reservation instanceof DeliveryLedger.Reservation.Capped) return stop(claim, "SEND_CAP", false, start);
        if (!(reservation instanceof DeliveryLedger.Reservation.Reserved reserved)) return DONE;

        boolean roomForRequest = waitLimit(start).compareTo(MIN_HTTP) > 0;
        Supplier<Duration> afterReservation = roomForRequest ? () -> waitLimit(start).minus(MIN_HTTP) : cleanupWaits(start);
        ledger.boundWaits(afterReservation.get());
        if (!ledger.lockReserved(claim, reserved.attemptNo(), key)) return DONE;
        ledger.boundWaits(afterReservation.get());
        String reason = !roomForRequest ? "DEADLINE" : !job.stillEligible(jdbc) ? job.cancelReason() : null;
        Duration timeout = min(HTTP_MAX, waitLimit(start));  // measured right before the request
        if (reason == null && timeout.compareTo(MIN_HTTP) < 0) reason = "DEADLINE";
        if (reason != null) {
            Supplier<Duration> cleanup = roomForRequest ? cleanupWaits(start) : afterReservation;
            ledger.boundWaits(cleanup.get());
            ledger.recordOutcome(reserved.attemptId(), SendOutcome.Result.of(SendOutcome.NOT_SENT));
            ledger.boundWaits(cleanup.get());
            ledger.finish(claim, false, reason, !reason.equals("DEADLINE"));
            return DONE;
        }
        SendOutcome.Result sent = mail.send(payload, key, timeout);
        Supplier<Duration> cleanup = cleanupWaits(start);
        ledger.boundWaits(cleanup.get());
        ledger.recordOutcome(reserved.attemptId(), sent);
        if (sent.outcome() == SendOutcome.SUCCESS) {
            ledger.boundWaits(cleanup.get());
            ledger.finish(claim, true, null, false);
            return DONE;
        }
        // A request cannot wait for the next quota window: quota and permanent errors end here.
        if (sent.outcome().kind == SendOutcome.Kind.STOP || sent.outcome().kind == SendOutcome.Kind.NEXT_WINDOW)
            return finished(claim, sent.outcome().name(), cleanup);
        if (reserved.attemptNo() >= DeliveryLedger.MAX_ATTEMPTS) return finished(claim, "ATTEMPTS_EXHAUSTED", cleanup);
        Duration wait = BACKOFF[Math.min(round, BACKOFF.length - 1)];
        if (sent.retryAfter() != null && sent.retryAfter().compareTo(wait) > 0) return finished(claim, "RATE_LIMITED", cleanup);  // 12 §6
        if (!roomForNext(start, wait)) return finished(claim, "DEADLINE", cleanup);
        return new Round(Step.NEXT, wait);
    }

    private Round stop(DeliveryLedger.Claim claim, String reason, boolean cancel, long start) {
        ledger.boundWaits(cleanupWaits(start).get());
        ledger.finish(claim, false, reason, cancel);
        return DONE;
    }

    private Round finished(DeliveryLedger.Claim claim, String reason, Supplier<Duration> cleanup) {
        ledger.boundWaits(cleanup.get());
        ledger.finish(claim, false, reason, false);
        return DONE;
    }

    private void end(DeliveryLedger.Claim claim, String reason, long start) {
        limits.requirePoolRoom(() -> remaining(start));
        tx.executeWithoutResult(status -> {
            ledger.boundWaits(cleanupWaits(start).get());
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
            // A retry is measured again after its sleep, which may overrun: still a failed test, not a 503 (agy C4 round 2)
            if (no > 1 ? !roomForNext(start, Duration.ZERO) : remaining(start).compareTo(MIN_START) < 0) break;
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
            if (!roomForNext(start, wait) || !sleep(wait)) break;  // agy C4-R1: a failed test (502), not a budget error (503)
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
