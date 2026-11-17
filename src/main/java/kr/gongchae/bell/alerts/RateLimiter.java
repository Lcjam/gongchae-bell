package kr.gongchae.bell.alerts;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.function.Supplier;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * ADR-0010 fixed-window DB counters. Windows are aligned to the epoch, so a day window is a UTC day (the Resend quota
 * day) and an hour window is a clock hour. Every call commits on its own: a later failure of the request never
 * refunds a counted attempt.
 */
@Component
public class RateLimiter {
    public enum Scope { SUBSCRIBE, RESEND, WEBHOOK_TEST, TOKEN_FAIL, SEND_TOTAL }

    /** ADR-0014 initial values. */
    public record Limit(int max, Duration window) {}
    public static final Limit PER_IP = new Limit(10, Duration.ofMinutes(10));
    public static final Limit PER_TARGET = new Limit(3, Duration.ofHours(1));
    public static final Limit TOKEN_FAIL = new Limit(30, Duration.ofMinutes(10));
    public static final Limit EMAIL_TOTAL = new Limit(90, Duration.ofDays(1));
    public static final Limit DISCORD_TOTAL = new Limit(300, Duration.ofHours(1));

    private final JdbcClient jdbc;
    private final Clock clock;
    private final TransactionTemplate independent;

    public RateLimiter(JdbcClient jdbc, Clock clock, PlatformTransactionManager manager) {
        this.jdbc = jdbc; this.clock = clock;
        independent = new TransactionTemplate(manager);
        independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Counts one request and says whether it is still within {@code max}. Commits on its own. */
    public boolean acquire(Scope scope, byte[] key, Limit limit) {
        return Boolean.TRUE.equals(independent.execute(status -> acquireHere(scope, key, limit.max(), limit.window())));
    }

    /**
     * {@link #acquire} inside an in-request budget (14-4): {@code waitLimit} is read once the connection is taken and
     * bounds the lock and statement waits of the count.
     */
    public boolean acquire(Scope scope, byte[] key, Limit limit, Supplier<Duration> waitLimit) {
        return Boolean.TRUE.equals(independent.execute(status -> {
            boundWaits(waitLimit.get());
            return acquireHere(scope, key, limit.max(), limit.window());
        }));
    }

    /**
     * Cuts {@code lock_timeout} and {@code statement_timeout} of the current transaction to {@code limit}. Less than
     * 1 ms left means the step must not start: {@link QueryTimeoutException} without running anything (never a 1 ms
     * limit that fails the next statement at random).
     */
    public void boundWaits(Duration limit) {
        if (limit.compareTo(Duration.ofMillis(1)) < 0) throw new QueryTimeoutException("SYNC_BUDGET_SPENT");
        jdbc.sql("SELECT set_config('lock_timeout', :ms, true), set_config('statement_timeout', :ms, true)")
                .param("ms", limit.toMillis() + "ms").query().singleRow();
    }

    /** The same as {@link #acquire} inside the caller's transaction (used by the send reservation R). */
    boolean acquireHere(Scope scope, byte[] key, int max, Duration window) {
        Instant start = windowStart(window);
        return jdbc.sql("""
                INSERT INTO rate_limit(scope, key_hmac, window_start, window_end, count) VALUES (:scope, :key, :start, :end, 1)
                ON CONFLICT (scope, key_hmac, window_start) DO UPDATE SET count = rate_limit.count + 1
                WHERE rate_limit.count < :max RETURNING count
                """).param("scope", scope.name()).param("key", key).param("start", utc(start))
                .param("end", utc(start.plus(window))).param("max", max).query(Integer.class).optional().isPresent();
    }

    /** Counts one event without a ceiling (token failures). */
    public void hit(Scope scope, byte[] key, Limit limit) {
        independent.executeWithoutResult(status -> acquireHere(scope, key, Integer.MAX_VALUE, limit.window()));
    }

    public int count(Scope scope, byte[] key, Limit limit) {
        return jdbc.sql("SELECT count FROM rate_limit WHERE scope = :scope AND key_hmac = :key AND window_start = :start")
                .param("scope", scope.name()).param("key", key).param("start", utc(windowStart(limit.window())))
                .query(Integer.class).optional().orElse(0);
    }

    /** Start of the next window: when a refused request may be counted again. */
    public Instant nextWindow(Duration window) { return windowStart(window).plus(window); }

    private Instant windowStart(Duration window) {
        long size = window.toSeconds();
        long now = clock.instant().getEpochSecond();
        return Instant.ofEpochSecond(now - Math.floorMod(now, size));
    }

    private static OffsetDateTime utc(Instant instant) { return instant.atOffset(ZoneOffset.UTC); }
}
