package kr.gongchae.bell.alerts;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * In-request sends (contract 12 §6, 14-4): confirmation and management-link mails, and the Discord registration
 * test. One budget of {@link #DEADLINE} from the request start covers the sign-up transaction, pool/lock/statement
 * waits, R, HTTP, back-off and the final write, measured on a monotonic timer. "At most 3" means at most three
 * attempts: none starts with less than {@link #MIN_START} left, every wait and timeout is cut to the remaining budget
 * minus {@link #CLEANUP}, and the budget is checked again after R right before the HTTP request. A GC pause, OS stall
 * or DB outage can still stretch the real response; the budget bounds what this code starts.
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

    private enum Step { DONE, NEXT }
    private record Round(Step step, SendOutcome.Result result) {}

    /** Delivers one synchronous mail job already claimed by this request; the job row records the outcome. */
    public void deliverMail(DeliveryLedger.Claim claim, MailJob job, long start) {
        byte[] payload = job.payload();
        byte[] hash = AlertsCrypto.sha256(payload);
        byte[] totalKey = crypto.limitKey("SEND_TOTAL", "email");
        for (int round = 0; ; round++) {
            if (left(start).compareTo(MIN_START) < 0) { end(claim, "DEADLINE"); return; }
            Round result = tx.execute(status -> {
                ledger.boundWaits(left(start).minus(CLEANUP));
                if (!job.stillEligible(jdbc)) {
                    ledger.finish(claim, false, job.cancelReason(), true);
                    return new Round(Step.DONE, null);
                }
                DeliveryLedger.History history = ledger.history(claim.id());
                UUID key = history.key();
                if (history.allDefinite()) key = UUID.randomUUID();  // nothing went out: a fresh key cannot duplicate
                else if (history.anyUnknown() && !Arrays.equals(history.payloadHash(), hash)) {
                    ledger.finish(claim, false, "BODY_CHANGED", false);
                    return new Round(Step.DONE, null);
                }
                DeliveryLedger.Reservation reservation = ledger.reserve(claim, key, hash, totalKey, RateLimiter.EMAIL_TOTAL, left(start).minus(CLEANUP));
                if (reservation instanceof DeliveryLedger.Reservation.Capped) {
                    ledger.finish(claim, false, "SEND_CAP", false);
                    return new Round(Step.DONE, null);
                }
                if (!(reservation instanceof DeliveryLedger.Reservation.Reserved reserved)) return new Round(Step.DONE, null);
                if (!ledger.lockReserved(claim, reserved.attemptNo(), key)) return new Round(Step.DONE, null);
                Duration timeout = min(HTTP_MAX, left(start).minus(CLEANUP));
                if (!job.stillEligible(jdbc) || timeout.compareTo(MIN_HTTP) < 0) {
                    ledger.recordOutcome(reserved.attemptId(), SendOutcome.Result.of(SendOutcome.NOT_SENT));
                    boolean cancelled = timeout.compareTo(MIN_HTTP) >= 0;
                    ledger.finish(claim, false, cancelled ? job.cancelReason() : "DEADLINE", cancelled);
                    return new Round(Step.DONE, null);
                }
                SendOutcome.Result sent = mail.send(payload, key, timeout);
                ledger.recordOutcome(reserved.attemptId(), sent);
                if (sent.outcome() == SendOutcome.SUCCESS) {
                    ledger.finish(claim, true, null, false);
                    return new Round(Step.DONE, sent);
                }
                // A request cannot wait for the next quota window: quota and permanent errors end here.
                if (sent.outcome().kind == SendOutcome.Kind.STOP || sent.outcome().kind == SendOutcome.Kind.NEXT_WINDOW) {
                    ledger.finish(claim, false, sent.outcome().name(), false);
                    return new Round(Step.DONE, sent);
                }
                if (reserved.attemptNo() >= DeliveryLedger.MAX_ATTEMPTS) {
                    ledger.finish(claim, false, "ATTEMPTS_EXHAUSTED", false);
                    return new Round(Step.DONE, sent);
                }
                return new Round(Step.NEXT, sent);
            });
            if (result == null || result.step() == Step.DONE) return;
            Duration wait = BACKOFF[Math.min(round, BACKOFF.length - 1)];
            if (result.result().retryAfter() != null && result.result().retryAfter().compareTo(wait) > 0)
                { end(claim, "RATE_LIMITED"); return; }  // 12 §6: a longer server wait ends the request
            if (left(start).minus(wait).compareTo(MIN_START) < 0) { end(claim, "DEADLINE"); return; }
            if (!sleep(wait)) { end(claim, "INTERRUPTED"); return; }
        }
    }

    private void end(DeliveryLedger.Claim claim, String reason) {
        tx.executeWithoutResult(status -> ledger.finish(claim, false, reason, false));
    }

    /** One attempt of the Discord registration test, kept in memory: there is no row before the test succeeds. */
    public record WebhookAttempt(int attemptNo, SendOutcome.Result result) {}

    /**
     * Registration test (12 §6): no subscription or notification row exists yet, so each attempt is charged only to
     * the Discord hourly SEND_TOTAL (committed before the request, never refunded). Attempts come back for the
     * success-only audit rows.
     */
    public List<WebhookAttempt> testWebhook(WebhookUrl url, byte[] payload, long start) {
        List<WebhookAttempt> attempts = new ArrayList<>();
        byte[] totalKey = crypto.limitKey("SEND_TOTAL", "discord");
        for (int no = 1; no <= DeliveryLedger.MAX_ATTEMPTS; no++) {
            if (left(start).compareTo(MIN_START) < 0) break;
            if (!limits.acquire(RateLimiter.Scope.SEND_TOTAL, totalKey, RateLimiter.DISCORD_TOTAL)) break;
            Duration timeout = min(HTTP_MAX, left(start).minus(CLEANUP));
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
