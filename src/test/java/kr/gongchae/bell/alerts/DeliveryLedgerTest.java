package kr.gongchae.bell.alerts;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Contract 12/13/14 bookkeeping against PostgreSQL 17, with a scripted provider and a virtual monotonic clock. */
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class DeliveryLedgerTest {
    @Container static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17");

    static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-11-16T03:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    /** Virtual monotonic time: sleeping and scripted provider calls advance it; nothing really waits. */
    static final class VirtualTimer implements SyncSender.Timer {
        long nanos;
        Runnable onSleep = () -> {};
        @Override public long nanoTime() { return nanos; }
        @Override public void sleep(Duration duration) { nanos += duration.toNanos(); onSleep.run(); }
        void advance(Duration duration) { nanos += duration.toNanos(); }
    }

    /** Scripted provider: each call takes the next step, which may advance virtual time. Records keys and timeouts. */
    static final class ScriptedMail implements MailTransport {
        final Deque<Function<Duration, SendOutcome.Result>> steps = new ArrayDeque<>();
        final List<UUID> keys = new ArrayList<>();
        final List<Duration> timeouts = new ArrayList<>();
        @Override public SendOutcome.Result send(byte[] payload, UUID key, Duration timeout) {
            keys.add(key);
            timeouts.add(timeout);
            return steps.removeFirst().apply(timeout);
        }
    }

    /** Hands out each transaction after a scripted connection-pool wait in virtual time (none when the script is empty). */
    final class SlowPool implements PlatformTransactionManager {
        final Deque<Duration> waits = new ArrayDeque<>();
        @Override public TransactionStatus getTransaction(TransactionDefinition definition) {
            Duration wait = waits.pollFirst();
            if (wait != null) timer.advance(wait);
            return manager.getTransaction(definition);
        }
        @Override public void commit(TransactionStatus status) { manager.commit(status); }
        @Override public void rollback(TransactionStatus status) { manager.rollback(status); }
    }

    static final WebhookTransport NO_WEBHOOK = (url, payload, timeout) -> { throw new AssertionError("no webhook here"); };

    DriverManagerDataSource ds;
    JdbcClient jdbc;
    JdbcTransactionManager manager;
    MutableClock clock;
    VirtualTimer timer;
    ScriptedMail mail;
    RateLimiter limits;
    DeliveryLedger ledger;
    SyncSender sender;
    AlertsCrypto crypto;

    @BeforeEach
    void setUp() {
        ds = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway.configure().dataSource(ds).load().migrate();
        jdbc = JdbcClient.create(ds);
        manager = new JdbcTransactionManager(ds);
        jdbc.sql("TRUNCATE subscription, notification, notification_attempt, notification_item, rate_limit CASCADE").update();
        clock = new MutableClock();
        timer = new VirtualTimer();
        mail = new ScriptedMail();
        crypto = new AlertsCrypto(AlertsSecurityTest.enabled());
        limits = new RateLimiter(jdbc, clock, manager);
        ledger = new DeliveryLedger(jdbc, clock, limits, manager);
        sender = sender(ledger);
    }

    SyncSender sender(DeliveryLedger with) {
        return new SyncSender(jdbc, manager, with, limits, mail, NO_WEBHOOK, crypto, timer);
    }

    record Pending(UUID id, byte[] hash, DeliveryLedger.Claim claim) {}

    Pending pending() {
        UUID id = UUID.randomUUID();
        byte[] hash = AlertsCrypto.sha256(UUID.randomUUID().toString());
        jdbc.sql("""
                INSERT INTO subscription(id, channel, status, email, dest_hmac, conditions, notify_new, notify_deadline,
                  confirm_token_hash, confirm_expires_at, consent_version, consented_at, age_confirmed, created_at, updated_at)
                VALUES (:id, 'EMAIL', 'PENDING', 'someone@example.com', :dest, '{}'::jsonb, true, false, :hash, :expires, 'v1', :now, true, :now, :now)
                """).param("id", id).param("dest", id.toString().getBytes(UTF_8)).param("hash", hash)
                .param("expires", OffsetDateTime.ofInstant(clock.now.plus(Duration.ofHours(24)), ZoneOffset.UTC))
                .param("now", OffsetDateTime.ofInstant(clock.now, ZoneOffset.UTC)).update();
        return new Pending(id, hash, ledger.createSync(id, "EMAIL_CONFIRM"));
    }

    SyncSender.MailJob job(Pending p) {
        return new SyncSender.MailJob() {
            @Override public boolean stillEligible(JdbcClient db) {
                return db.sql("SELECT 1 FROM subscription WHERE id = :id AND status = 'PENDING' AND confirm_token_hash = :h FOR SHARE")
                        .param("id", p.id()).param("h", p.hash()).query(Integer.class).optional().isPresent();
            }
            @Override public String cancelReason() { return "SUPERSEDED"; }
            @Override public byte[] payload() { return "{\"to\":[\"someone@example.com\"],\"text\":\"confirm\"}".getBytes(UTF_8); }
        };
    }

    void deliver(Pending p) { sender.deliverMail(p.claim(), job(p), timer.nanoTime()); }

    String job(long id) {
        return jdbc.sql("SELECT status || '|' || coalesce(last_error, '-') || '|' || attempts FROM notification WHERE id = :id")
                .param("id", id).query(String.class).single();
    }

    List<String> attempts(long id) {
        return jdbc.sql("SELECT coalesce(outcome, 'NULL') FROM notification_attempt WHERE notification_id = :id ORDER BY attempt_no")
                .param("id", id).query(String.class).list();
    }

    int emailTotal() {
        return jdbc.sql("SELECT coalesce(sum(count), 0) FROM rate_limit WHERE scope = 'SEND_TOTAL'").query(Integer.class).single();
    }

    static Function<Duration, SendOutcome.Result> outcome(SendOutcome outcome) { return timeout -> SendOutcome.Result.of(outcome); }

    @Test
    void aReservationSurvivesACrashAndItsUnknownAttemptIsSweptNeverResent() {
        Pending p = pending();
        mail.steps.add(timeout -> { throw new IllegalStateException("simulated crash after R, before the result"); });
        assertThatThrownBy(() -> deliver(p)).isInstanceOf(IllegalStateException.class);  // A rolled back
        assertThat(attempts(p.claim().id())).containsExactly("NULL");
        assertThat(emailTotal()).as("the reservation is not refunded").isEqualTo(1);
        assertThat(job(p.claim().id())).isEqualTo("SENDING|-|1");
        assertThat(ledger.sweepSync()).as("not yet stale").isZero();
        clock.now = clock.now.plus(Duration.ofMinutes(6));
        assertThat(ledger.sweepSync()).isEqualTo(1);
        assertThat(job(p.claim().id())).isEqualTo("UNCERTAIN|INTERRUPTED|1");
        assertThat(attempts(p.claim().id())).containsExactly("UNKNOWN_CRASH");
        assertThat(mail.keys).hasSize(1);
    }

    @Test
    void theLocalCeilingReservesNothingAndSendsNothing() {
        Pending p = pending();
        for (int i = 0; i < 90; i++) assertThat(limits.acquire(RateLimiter.Scope.SEND_TOTAL, crypto.limitKey("SEND_TOTAL", "email"), RateLimiter.EMAIL_TOTAL)).isTrue();
        deliver(p);
        assertThat(mail.keys).isEmpty();
        assertThat(attempts(p.claim().id())).isEmpty();
        assertThat(emailTotal()).isEqualTo(90);
        assertThat(job(p.claim().id())).isEqualTo("FAILED|SEND_CAP|0");
    }

    @Test
    void aDailyQuota429ReachedTheProviderSoItIsAnAttemptAndUsesTheReservation() {
        Pending p = pending();
        mail.steps.add(outcome(SendOutcome.QUOTA_DAILY));
        deliver(p);
        assertThat(mail.keys).hasSize(1);
        assertThat(attempts(p.claim().id())).containsExactly("QUOTA_DAILY");
        assertThat(emailTotal()).isEqualTo(1);
        assertThat(job(p.claim().id())).isEqualTo("FAILED|QUOTA_DAILY|1");
    }

    @Test
    void atMostThreeAttemptsAndEvery5xxStaysUnknown() {
        Pending p = pending();
        for (int i = 0; i < 3; i++) mail.steps.add(outcome(SendOutcome.HTTP_5XX));
        deliver(p);
        assertThat(mail.keys).hasSize(3).as("same key: an earlier attempt may have been processed").containsOnly(mail.keys.getFirst());
        assertThat(attempts(p.claim().id())).containsExactly("HTTP_5XX", "HTTP_5XX", "HTTP_5XX");
        assertThat(emailTotal()).isEqualTo(3);
        assertThat(job(p.claim().id())).isEqualTo("UNCERTAIN|ATTEMPTS_EXHAUSTED|3");
        assertThat(timer.nanos).isEqualTo(Duration.ofSeconds(6).toNanos());  // 2 s and 4 s waits
    }

    @Test
    void aLaterDefiniteRejectionNeverErasesAnEarlierUnknownAndGeneric4xxDoesNotDisable() {
        Pending p = pending();
        mail.steps.add(outcome(SendOutcome.TIMEOUT));
        mail.steps.add(outcome(SendOutcome.REQUEST_4XX));
        deliver(p);
        assertThat(attempts(p.claim().id())).containsExactly("TIMEOUT", "REQUEST_4XX");
        assertThat(job(p.claim().id())).isEqualTo("UNCERTAIN|REQUEST_4XX|2");
        Pending q = pending();
        mail.steps.add(outcome(SendOutcome.REQUEST_4XX));
        deliver(q);
        assertThat(job(q.claim().id())).isEqualTo("FAILED|REQUEST_4XX|1");
        assertThat(jdbc.sql("SELECT count(*) FROM subscription WHERE status = 'PENDING'").query(Integer.class).single())
                .as("a generic request error never disables a subscription").isEqualTo(2);
    }

    @Test
    void aFreshKeyOnlyAfterDefiniteRejectionsAndTheSameKeyAfterAnUnknown() {
        Pending p = pending();
        mail.steps.add(outcome(SendOutcome.RATE_LIMITED));
        mail.steps.add(outcome(SendOutcome.SUCCESS));
        deliver(p);
        assertThat(mail.keys.get(0)).isNotEqualTo(mail.keys.get(1));
        assertThat(job(p.claim().id())).isEqualTo("SENT|-|2");
        Pending q = pending();
        mail.keys.clear();
        mail.steps.add(outcome(SendOutcome.TIMEOUT));
        mail.steps.add(outcome(SendOutcome.SUCCESS));
        deliver(q);
        assertThat(mail.keys.get(0)).isEqualTo(mail.keys.get(1));
        assertThat(job(q.claim().id())).isEqualTo("SENT|-|2");
        assertThat(jdbc.sql("SELECT status FROM subscription WHERE id = :id").param("id", q.id()).query(String.class).single())
                .as("a sent confirmation mail never activates the subscription").isEqualTo("PENDING");
    }

    @Test
    void afterAnUnknownAttemptAChangedEligibilityStopsWithoutAnotherReservationOrRequest() {
        Pending p = pending();
        mail.steps.add(outcome(SendOutcome.TIMEOUT));
        timer.onSleep = () -> jdbc.sql("UPDATE subscription SET confirm_token_hash = :h WHERE id = :id")  // a newer sign-up replaced the token
                .param("h", AlertsCrypto.sha256("newer")).param("id", p.id()).update();
        deliver(p);
        assertThat(mail.keys).hasSize(1);
        assertThat(emailTotal()).isEqualTo(1);
        assertThat(job(p.claim().id())).isEqualTo("UNCERTAIN|SUPERSEDED|1");
    }

    @Test
    void theWholeRequestStaysInsideTwentyFiveSecondsIncludingWaitsInsideR() {
        Pending p = pending();
        mail.steps.add(timeout -> { timer.advance(timeout); return SendOutcome.Result.of(SendOutcome.TIMEOUT); });
        mail.steps.add(timeout -> { timer.advance(timeout); return SendOutcome.Result.of(SendOutcome.TIMEOUT); });
        deliver(p);
        // t=0 attempt 1 (10 s) -> wait 2 -> t=12 attempt 2 (min(10, 25-12-2)=10) -> t=22: a 4 s wait would leave < 5 s
        assertThat(mail.timeouts).containsExactly(Duration.ofSeconds(10), Duration.ofSeconds(10));
        assertThat(job(p.claim().id())).isEqualTo("UNCERTAIN|DEADLINE|2");
        assertThat(timer.nanos).isLessThanOrEqualTo(Duration.ofSeconds(25).toNanos());

        // A slow start of R (its pool wait) is measured inside R: with no room left for a request, nothing is reserved.
        var slowLedger = new DeliveryLedger(jdbc, clock, limits, manager) {
            int calls;
            @Override public Reservation reserve(Claim claim, UUID key, byte[] hash, byte[] totalKey, RateLimiter.Limit total, java.util.function.Supplier<Duration> waitLimit) {
                timer.advance(Duration.ofSeconds(++calls == 1 ? 9 : 12));
                return super.reserve(claim, key, hash, totalKey, total, waitLimit);
            }
        };
        sender = sender(slowLedger);
        mail.timeouts.clear();
        Pending q = pending();
        long start = timer.nanoTime();
        int totalBefore = emailTotal();
        mail.steps.add(timeout -> { timer.advance(Duration.ofSeconds(1)); return SendOutcome.Result.of(SendOutcome.HTTP_5XX); });
        sender.deliverMail(q.claim(), job(q), start);
        // attempt 1: R starts at 9 s, timeout min(10, 25-9-2)=10, 5xx at 10 s; wait 2 -> 12 s; R starts at 24 s: 1 s left
        // is less than CLEANUP + one request, so R reserves nothing (before: a NOT_SENT attempt and one more unit)
        assertThat(mail.timeouts).containsExactly(Duration.ofSeconds(10));
        assertThat(attempts(q.claim().id())).containsExactly("HTTP_5XX");
        assertThat(emailTotal() - totalBefore).isEqualTo(1);
        assertThat(job(q.claim().id())).isEqualTo("UNCERTAIN|DEADLINE|1");
        assertThat(timer.nanoTime() - start).isLessThanOrEqualTo(Duration.ofSeconds(25).toNanos());

        // A slow end of R (after it committed) is measured again before the request: the reserved attempt is NOT_SENT.
        var lateCommit = new DeliveryLedger(jdbc, clock, limits, manager) {
            @Override public Reservation reserve(Claim claim, UUID key, byte[] hash, byte[] totalKey, RateLimiter.Limit total, java.util.function.Supplier<Duration> waitLimit) {
                Reservation reservation = super.reserve(claim, key, hash, totalKey, total, waitLimit);
                timer.advance(Duration.ofSeconds(22));
                return reservation;
            }
        };
        sender = sender(lateCommit);
        mail.timeouts.clear();
        Pending r = pending();
        sender.deliverMail(r.claim(), job(r), timer.nanoTime());
        assertThat(mail.timeouts).isEmpty();
        assertThat(attempts(r.claim().id())).containsExactly("NOT_SENT");
        assertThat(job(r.claim().id())).isEqualTo("FAILED|DEADLINE|1");
    }

    @Test
    void aPoolWaitBeforeEitherTransactionIsMeasuredSoTheJobEndsWithoutAReservationOrRequest() {
        var pool = new SlowPool();
        var poolLimits = new RateLimiter(jdbc, clock, pool);
        var poolLedger = new DeliveryLedger(jdbc, clock, poolLimits, pool);
        sender = new SyncSender(jdbc, pool, poolLedger, poolLimits, mail, NO_WEBHOOK, crypto, timer);
        // A gets its connection after 21 s: 4 s left. agy M-1: before, A set a 1 ms limit, the next statement failed and
        // the row stayed SENDING until the sweep. Now A measures again and ends the job inside the CLEANUP share.
        Pending a = pending();
        pool.waits.add(Duration.ofSeconds(21));
        deliver(a);
        assertThat(job(a.claim().id())).isEqualTo("FAILED|DEADLINE|0");
        // R gets connection B after 22 s: 3 s left is not CLEANUP plus one request, so R reserves nothing.
        Pending b = pending();
        pool.waits.addAll(List.of(Duration.ZERO, Duration.ofSeconds(22)));
        deliver(b);
        assertThat(job(b.claim().id())).isEqualTo("FAILED|DEADLINE|0");
        assertThat(attempts(b.claim().id())).isEmpty();
        assertThat(mail.keys).isEmpty();
        assertThat(emailTotal()).isZero();
    }

    @Test
    void aRowLockHeldByAnotherTransactionIsWaitedForOnlyWithinTheBudgetAndTheJobStillEnds() throws Exception {
        Pending p = pending();
        try (Connection holder = ds.getConnection()) {  // e.g. a condition change or an unsubscribe holding the row
            holder.setAutoCommit(false);
            try (var lock = holder.prepareStatement("SELECT 1 FROM subscription WHERE id = ? FOR UPDATE")) {
                lock.setObject(1, p.id());
                lock.executeQuery();
            }
            long t0 = System.nanoTime();
            sender.deliverMail(p.claim(), job(p), timer.nanoTime() - Duration.ofSeconds(20).toNanos());  // 5 s left: waits get 3 s
            Duration took = Duration.ofNanos(System.nanoTime() - t0);
            holder.rollback();
            assertThat(took).isBetween(Duration.ofMillis(2_500), Duration.ofMillis(6_000));
        }
        assertThat(mail.keys).isEmpty();
        assertThat(emailTotal()).isZero();
        assertThat(job(p.claim().id())).as("ended, not left SENDING for the sweep").isEqualTo("FAILED|DEADLINE|0");
    }

    @Test
    void aCountWaitsForAHeldRateLimitRowOnlyAsLongAsItsBudgetAndCommitsNothingWhenItRunsOut() throws Exception {
        byte[] key = crypto.limitKey("SUBSCRIBE:ip", "198.51.100.30");
        assertThat(limits.acquire(RateLimiter.Scope.SUBSCRIBE, key, RateLimiter.PER_IP)).isTrue();
        try (Connection holder = ds.getConnection()) {  // another request counting the same key, not yet committed
            holder.setAutoCommit(false);
            try (var count = holder.createStatement()) { count.executeUpdate("UPDATE rate_limit SET count = count + 1 WHERE scope = 'SUBSCRIBE'"); }
            long t0 = System.nanoTime();
            // lock_timeout and statement_timeout are the same value: either may fire first, and both are handled alike
            assertThatThrownBy(() -> limits.acquire(RateLimiter.Scope.SUBSCRIBE, key, RateLimiter.PER_IP, () -> Duration.ofSeconds(2)))
                    .isInstanceOfAny(CannotAcquireLockException.class, QueryTimeoutException.class);
            assertThat(Duration.ofNanos(System.nanoTime() - t0)).isBetween(Duration.ofMillis(1_500), Duration.ofMillis(5_000));
            assertThatThrownBy(() -> limits.acquire(RateLimiter.Scope.SUBSCRIBE, key, RateLimiter.PER_IP, () -> Duration.ZERO))
                    .as("a spent budget starts no statement").isInstanceOf(QueryTimeoutException.class).hasMessage("SYNC_BUDGET_SPENT");
            holder.rollback();
        }
        assertThat(jdbc.sql("SELECT count FROM rate_limit WHERE scope = 'SUBSCRIBE'").query(Integer.class).single())
                .as("only the first count; the timed-out one committed nothing").isEqualTo(1);
    }

    @Test
    void aProviderWaitOfADayEndsTheRequestAfterOneRequestForMailAndWebhookTests() {
        Pending p = pending();
        mail.steps.add(timeout -> new SendOutcome.Result(SendOutcome.RATE_LIMITED, 429, HttpTransports.MAX_WAIT));
        deliver(p);
        assertThat(mail.keys).hasSize(1);
        assertThat(job(p.claim().id())).isEqualTo("FAILED|RATE_LIMITED|1");
        List<Duration> requests = new ArrayList<>();
        var discord = new SyncSender(jdbc, manager, ledger, limits, mail,
                (url, payload, timeout) -> { requests.add(timeout); return new SendOutcome.Result(SendOutcome.RATE_LIMITED, 429, HttpTransports.MAX_WAIT); },
                crypto, timer);
        var attempts = discord.testWebhook(WebhookUrl.parse("https://discord.com/api/webhooks/1/token").orElseThrow(), "{}".getBytes(UTF_8), timer.nanoTime());
        assertThat(attempts).hasSize(1);
        assertThat(requests).hasSize(1);
    }

    @Test
    void synchronousKindsAreNeverClaimedOrRecoveredByTheAsyncPathAndStaleClaimsAreFenced() {
        Pending confirm = pending();
        long digest = jdbc.sql("""
                INSERT INTO notification(subscription_id, kind, batch_key, status, next_attempt_at, created_at, idempotency_key)
                VALUES (:s, 'EMAIL_DIGEST', '2026-11-16', 'PENDING', :now, :now, :key) RETURNING id
                """).param("s", confirm.id()).param("now", OffsetDateTime.ofInstant(clock.now, ZoneOffset.UTC)).param("key", UUID.randomUUID())
                .query(Long.class).single();
        List<DeliveryLedger.Claim> claims = ledger.claimDue(10);
        assertThat(claims).extracting(DeliveryLedger.Claim::id).containsExactly(digest);
        clock.now = clock.now.plus(Duration.ofMinutes(6));
        assertThat(ledger.recoverAsync()).isEqualTo(1);
        assertThat(job(digest)).isEqualTo("RETRY|UNKNOWN_CRASH|0");
        assertThat(job(confirm.claim().id())).as("the async recovery leaves the sync row alone").isEqualTo("SENDING|-|0");
        assertThat(ledger.reserve(claims.getFirst(), UUID.randomUUID(), new byte[32], null, null, () -> Duration.ofSeconds(1)))
                .as("the old worker's claim no longer fences").isInstanceOf(DeliveryLedger.Reservation.Fenced.class);
        assertThat(ledger.finish(claims.getFirst(), true, null, false)).isFalse();
        assertThat(ledger.sweepSync()).isEqualTo(1);
        assertThat(job(confirm.claim().id())).isEqualTo("FAILED|INTERRUPTED|0");
    }

    @Test
    void aDeletedSubscriptionEndsTheLoopQuietlyAndALongProviderWaitEndsTheRequest() {
        Pending p = pending();
        mail.steps.add(outcome(SendOutcome.TIMEOUT));
        timer.onSleep = () -> jdbc.sql("DELETE FROM subscription WHERE id = :id").param("id", p.id()).update();  // unsubscribed meanwhile
        deliver(p);
        assertThat(mail.keys).hasSize(1);
        assertThat(jdbc.sql("SELECT count(*) FROM notification").query(Integer.class).single()).as("cascade").isZero();
        timer.onSleep = () -> {};
        Pending q = pending();
        mail.steps.add(timeout -> new SendOutcome.Result(SendOutcome.RATE_LIMITED, 429, Duration.ofSeconds(30)));
        deliver(q);
        assertThat(mail.keys).hasSize(2);
        assertThat(job(q.claim().id())).isEqualTo("FAILED|RATE_LIMITED|1");
    }

    @Test
    void restoreReappliesTheWholeJournalOrDeletesNothingWhenItIsMissingMisplacedOrCorrupt(@TempDir Path dir, CapturedOutput output) throws Exception {
        Pending a = pending();
        Pending b = pending();
        Path file = dir.resolve("erasure.log");
        // A path typo or a mount the restore missed, and a path that is a directory: failures, never "nothing to re-apply".
        for (Path wrong : List.of(file, dir)) {
            var command = new ErasureReapplyCommand(new ErasureJournal(wrong), jdbc, manager, new GenericApplicationContext());
            assertThatThrownBy(() -> command.run(null)).as(wrong.toString())
                    .isInstanceOf(ErasureJournal.CorruptJournal.class).hasMessage("ERASURE_LOG_MISSING");
        }
        Files.createFile(file);
        var journal = new ErasureJournal(file);
        journal.append(a.id(), clock.now);
        Files.writeString(file, "torn", StandardOpenOption.APPEND);
        var torn = new ErasureReapplyCommand(journal, jdbc, manager, new GenericApplicationContext());
        assertThatThrownBy(() -> torn.run(null)).isInstanceOf(ErasureJournal.CorruptJournal.class).hasMessage("ERASURE_LOG_TORN_LAST_LINE");
        assertThat(output).as("no completion is reported for a failed run").doesNotContain("ERASURE_REAPPLY_COMPLETE");
        assertThat(jdbc.sql("SELECT count(*) FROM subscription").query(Integer.class).single()).as("fail closed").isEqualTo(2);

        Files.writeString(file, "");
        assertThat(torn.reapply()).as("an existing empty journal: prepared, nothing erased yet").isZero();
        Files.writeString(file, a.id() + "\t" + clock.now + "\n" + b.id() + "\t" + clock.now + "\n");
        new ErasureReapplyCommand(journal, jdbc, manager, new GenericApplicationContext()).run(null);
        assertThat(output).contains("ERASURE_REAPPLY_COMPLETE deleted=2");
        assertThat(jdbc.sql("SELECT count(*) FROM subscription").query(Integer.class).single()).isZero();
    }

    @Test
    void maintenanceDeletesExpiredPendingOldDisabledOldFinishedJobsAndEndedCounters() {
        Pending expired = pending();
        Pending fresh = pending();
        jdbc.sql("UPDATE subscription SET confirm_expires_at = :past WHERE id = :id")
                .param("past", OffsetDateTime.ofInstant(clock.now.minusSeconds(1), ZoneOffset.UTC)).param("id", expired.id()).update();
        Pending disabled = pending();
        jdbc.sql("UPDATE subscription SET status = 'DISABLED', disabled_at = :at, disabled_reason = 'WEBHOOK_404' WHERE id = :id")
                .param("at", OffsetDateTime.ofInstant(clock.now.minus(Duration.ofDays(31)), ZoneOffset.UTC)).param("id", disabled.id()).update();
        jdbc.sql("UPDATE notification SET status = 'SENT', finished_at = :at, claimed_at = NULL WHERE id = :id")
                .param("at", OffsetDateTime.ofInstant(clock.now.minus(Duration.ofDays(31)), ZoneOffset.UTC)).param("id", fresh.claim().id()).update();
        limits.acquire(RateLimiter.Scope.SUBSCRIBE, crypto.limitKey("SUBSCRIBE:ip", "203.0.113.9"), RateLimiter.PER_IP);
        var maintenance = new AlertsMaintenance(jdbc, ledger, clock);
        assertThat(maintenance.run()).isEqualTo(new AlertsMaintenance.Removed(1, 1, 1, 0, 0));
        clock.now = clock.now.plus(Duration.ofMinutes(10));
        assertThat(maintenance.run().counters()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT id FROM subscription").query(UUID.class).list()).containsExactly(fresh.id());
    }
}
