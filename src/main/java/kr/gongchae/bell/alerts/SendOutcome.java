package kr.gongchae.bell.alerts;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

/**
 * Result of one provider request (contract 12 §4). UNKNOWN means the provider may have processed it: every 5xx,
 * a timeout or I/O error after the request started, Resend 409s and a crash with no recorded result. An unknown
 * attempt is never overwritten by a later result; it keeps the job out of FAILED/CANCELLED unless a 2xx follows.
 */
public enum SendOutcome {
    SUCCESS(Kind.SENT),
    TIMEOUT(Kind.UNKNOWN), IO_ERROR(Kind.UNKNOWN), HTTP_5XX(Kind.UNKNOWN), HTTP_409(Kind.UNKNOWN), UNKNOWN_CRASH(Kind.UNKNOWN),
    CONNECT_FAILED(Kind.RETRY), RATE_LIMITED(Kind.RETRY), NOT_SENT(Kind.RETRY),
    QUOTA_DAILY(Kind.NEXT_WINDOW),
    QUOTA_MONTHLY(Kind.STOP), REQUEST_4XX(Kind.STOP), ACCOUNT_4XX(Kind.STOP), REDIRECTED(Kind.STOP),
    WEBHOOK_404(Kind.STOP), WEBHOOK_401(Kind.STOP), BLOCKED_ADDRESS(Kind.STOP);

    public enum Kind { SENT, UNKNOWN, RETRY, NEXT_WINDOW, STOP }

    public final Kind kind;
    SendOutcome(Kind kind) { this.kind = kind; }

    public boolean unknown() { return kind == Kind.UNKNOWN; }

    /** Names stored in notification_attempt.outcome that count as "may have been sent". */
    public static final List<String> UNKNOWN_NAMES = Arrays.stream(values()).filter(SendOutcome::unknown).map(Enum::name).toList();

    /** One provider response. {@code retryAfter} is set only when the provider said how long to wait. */
    public record Result(SendOutcome outcome, Integer httpStatus, Duration retryAfter) {
        public static Result of(SendOutcome outcome) { return new Result(outcome, null, null); }
    }
}
