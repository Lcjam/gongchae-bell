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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.CannotCreateTransactionException;
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
        int acquisitions;
        int failAt;  // this acquisition (1-based) times out like a full Hikari pool; 0 = never
        @Override public TransactionStatus getTransaction(TransactionDefinition definition) {
            if (++acquisitions == failAt) throw new CannotCreateTransactionException("pool timeout (test)");
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
        assertThat(mail.timeouts).containsExactly(Duration.ofSeconds(10), Duration.ofSeconds(6));
        assertThat(job(p.claim().id())).isEqualTo("UNCERTAIN|DEADLINE|2");
        assertThat(timer.nanos).isEqualTo(Duration.ofSeconds(18).toNanos());

        var lateCommit = new DeliveryLedger(jdbc, clock, limits, manager) {
            @Override public Reservation reserve(Claim claim, UUID key, byte[] hash, byte[] totalKey, RateLimiter.Limit total,
                    java.util.function.Supplier<Duration> waitLimit) {
                Reservation reservation = super.reserve(claim, key, hash, totalKey, total, waitLimit);
                timer.advance(Duration.ofSeconds(17));
                return reservation;
            }
        };
        sender = sender(lateCommit);
        mail.timeouts.clear();
        Pending q = pending();
        sender.deliverMail(q.claim(), job(q), timer.nanoTime());
        assertThat(mail.timeouts).isEmpty();
        assertThat(attempts(q.claim().id())).containsExactly("NOT_SENT");
        assertThat(job(q.claim().id())).isEqualTo("FAILED|DEADLINE|1");
    }

    /** Codex PR13: a connection the pool cannot hand over (for A, or for R inside A) ends the job, not left SENDING. */
    @Test
    void aConnectionThePoolCannotHandOverEndsTheJobAsDeadline() {
        for (int failAt : new int[]{1, 2}) {  // 1 = A's connection, 2 = R's own connection B
            var pool = new SlowPool();
            var poolLimits = new RateLimiter(jdbc, clock, pool);
            sender = new SyncSender(jdbc, pool, new DeliveryLedger(jdbc, clock, poolLimits, pool), poolLimits, mail, NO_WEBHOOK, crypto, timer);
            pool.failAt = failAt;
            Pending p = pending();
            deliver(p);
            assertThat(job(p.claim().id())).as("failAt=%d", failAt).isEqualTo("FAILED|DEADLINE|0");
            assertThat(attempts(p.claim().id())).isEmpty();
        }
        assertThat(mail.keys).isEmpty();
        assertThat(emailTotal()).isZero();
    }

    @Test
    void insufficientPoolRoomStartsNoNewAttemptAndKeepsTheFinalConnectionShare() {
        var pool = new SlowPool();
        var poolLimits = new RateLimiter(jdbc, clock, pool);
        var poolLedger = new DeliveryLedger(jdbc, clock, poolLimits, pool);
        sender = new SyncSender(jdbc, pool, poolLedger, poolLimits, mail, NO_WEBHOOK, crypto, timer);
        Pending p = pending();
        pool.waits.add(Duration.ofSeconds(5));
        // 11 s left cannot admit A plus a final connection/write (5 + 5 + 2). Only the final TX is taken.
        sender.deliverMail(p.claim(), job(p), timer.nanoTime() - Duration.ofSeconds(14).toNanos());
        assertThat(pool.acquisitions).isEqualTo(1);
        assertThat(job(p.claim().id())).isEqualTo("FAILED|DEADLINE|0");
        int before = pool.acquisitions;
        assertThatThrownBy(() -> poolLedger.reserve(p.claim(), UUID.randomUUID(), new byte[32], null, null,
                () -> Duration.ofMillis(4_999))).isInstanceOf(QueryTimeoutException.class);
        assertThatThrownBy(() -> poolLimits.acquire(RateLimiter.Scope.SUBSCRIBE, new byte[32], RateLimiter.PER_IP,
                () -> Duration.ofMillis(4_999))).isInstanceOf(QueryTimeoutException.class);
        assertThat(pool.acquisitions).isEqualTo(before);
        assertThat(mail.keys).isEmpty();
        assertThat(emailTotal()).isZero();
    }

    @Test
    void poolAdmissionUsesTheConfiguredUpperBoundIncludingAnUnboundedPool() {
        var pool = new SlowPool();
        for (long configured : new long[]{10_000, 0}) {
            var configuredLimits = new RateLimiter(jdbc, clock, pool, configured);
            assertThatThrownBy(() -> configuredLimits.acquire(RateLimiter.Scope.SUBSCRIBE, new byte[32], RateLimiter.PER_IP,
                    () -> Duration.ofSeconds(9))).isInstanceOf(QueryTimeoutException.class);
        }
        assertThat(pool.acquisitions).isZero();
    }

    @Test
    void aRowLockHeldByAnotherTransactionIsWaitedForOnlyWithinTheBudgetAndTheJobStillEnds() throws Exception {
        Pending p = pending();
        var poolManager = new SlowPool();
        poolManager.waits.add(Duration.ofSeconds(5));
        sender = new SyncSender(jdbc, poolManager, ledger, limits, mail, NO_WEBHOOK, crypto, timer);
        try (Connection holder = ds.getConnection(); var pool = Executors.newSingleThreadExecutor()) {
            holder.setAutoCommit(false);
            try (var lock = holder.prepareStatement("SELECT 1 FROM subscription WHERE id = ? FOR UPDATE")) {
                lock.setObject(1, p.id());
                lock.executeQuery();
            }
            long t0 = System.nanoTime();
            Future<?> request = pool.submit(() -> sender.deliverMail(p.claim(), job(p), timer.nanoTime() - Duration.ofSeconds(10).toNanos()));
            try {
                request.get(8, TimeUnit.SECONDS);
                assertThat(Duration.ofNanos(System.nanoTime() - t0)).isBetween(Duration.ofMillis(2_500), Duration.ofMillis(6_000));
            } finally {
                holder.rollback();
            }
        }
        assertThat(mail.keys).isEmpty();
        assertThat(emailTotal()).isZero();
        assertThat(job(p.claim().id())).isEqualTo("FAILED|DEADLINE|0");
    }

    @Test
    void aCountWaitsForAHeldRateLimitRowOnlyAsLongAsItsBudgetAndCommitsNothingWhenItRunsOut() throws Exception {
        byte[] key = crypto.limitKey("SUBSCRIBE:ip", "198.51.100.30");
        assertThat(limits.acquire(RateLimiter.Scope.SUBSCRIBE, key, RateLimiter.PER_IP)).isTrue();
        var poolManager = new SlowPool();
        var boundedLimits = new RateLimiter(jdbc, clock, poolManager);
        poolManager.waits.add(Duration.ofSeconds(5));
        long start = timer.nanoTime();
        try (Connection holder = ds.getConnection(); var pool = Executors.newSingleThreadExecutor()) {
            holder.setAutoCommit(false);
            try (var count = holder.createStatement()) { count.executeUpdate("UPDATE rate_limit SET count = count + 1 WHERE scope = 'SUBSCRIBE'"); }
            long t0 = System.nanoTime();
            Future<?> request = pool.submit(() -> boundedLimits.acquire(RateLimiter.Scope.SUBSCRIBE, key, RateLimiter.PER_IP,
                    () -> Duration.ofSeconds(7).minusNanos(timer.nanoTime() - start)));
            try {
                assertThatThrownBy(() -> request.get(6, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                        .hasCauseInstanceOf(org.springframework.dao.DataAccessException.class);
                assertThat(Duration.ofNanos(System.nanoTime() - t0)).isBetween(Duration.ofMillis(1_500), Duration.ofMillis(5_000));
            } finally {
                holder.rollback();
            }
        }
        assertThat(jdbc.sql("SELECT count FROM rate_limit WHERE scope = 'SUBSCRIBE'").query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void sequentialReservationLocksShareOneDecreasingBudgetAndRollbackTogether() throws Exception {
        Pending p = pending();
        byte[] key = crypto.limitKey("SEND_TOTAL", "email");
        assertThat(limits.acquire(RateLimiter.Scope.SEND_TOTAL, key, RateLimiter.EMAIL_TOTAL)).isTrue();
        try (Connection notification = ds.getConnection(); Connection counter = ds.getConnection();
                var pool = Executors.newSingleThreadExecutor()) {
            notification.setAutoCommit(false);
            counter.setAutoCommit(false);
            try (var lock = notification.prepareStatement("SELECT 1 FROM notification WHERE id = ? FOR UPDATE")) {
                lock.setLong(1, p.claim().id());
                lock.executeQuery();
            }
            try (var lock = counter.createStatement()) { lock.executeUpdate("UPDATE rate_limit SET count = count + 1 WHERE scope = 'SEND_TOTAL'"); }
            long start = System.nanoTime();
            Future<?> reservation = pool.submit(() -> ledger.reserve(p.claim(), UUID.randomUUID(), new byte[32], key, RateLimiter.EMAIL_TOTAL,
                    () -> Duration.ofSeconds(6).minusNanos(System.nanoTime() - start)));
            try {
                awaitRowLock();
                Thread.sleep(2_000);
                notification.rollback();
                awaitRowLock();
                assertThatThrownBy(() -> reservation.get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                        .hasCauseInstanceOf(org.springframework.dao.DataAccessException.class);
                assertThat(Duration.ofNanos(System.nanoTime() - start)).isBetween(Duration.ofMillis(5_500), Duration.ofMillis(7_500));
            } finally {
                notification.rollback();
                counter.rollback();
            }
        }
        assertThat(job(p.claim().id())).isEqualTo("SENDING|-|0");
        assertThat(attempts(p.claim().id())).isEmpty();
        assertThat(emailTotal()).isEqualTo(1);
    }

    void awaitRowLock() throws InterruptedException {
        for (int n = 0; n < 100; n++) {
            if (jdbc.sql("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND datname = current_database()")
                    .query(Integer.class).single() > 0) return;
            Thread.sleep(20);
        }
        throw new AssertionError("reservation did not wait on a row lock");
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

    /** agy C4-R1: before, the retry slept and then failed the pool admission, and the sign-up answered 503. */
    @Test
    void aWebhookRetryThatCouldNotBeAdmittedEndsAsAFailedTestWithoutSleeping() {
        List<Duration> requests = new ArrayList<>();
        var discord = new SyncSender(jdbc, manager, ledger, limits, mail,
                (url, payload, timeout) -> { requests.add(timeout); timer.advance(timeout); return SendOutcome.Result.of(SendOutcome.TIMEOUT); },
                crypto, timer);
        long start = timer.nanoTime() - Duration.ofSeconds(1).toNanos();  // the steps before the test took 1 s
        // 10 s timeout -> 14 s left: after the 2 s wait, R's pool bound + request + final shares (13 s) no longer fit
        var attempts = discord.testWebhook(WebhookUrl.parse("https://discord.com/api/webhooks/1/token").orElseThrow(), "{}".getBytes(UTF_8), start);
        assertThat(attempts).extracting(a -> a.result().outcome()).containsExactly(SendOutcome.TIMEOUT);
        assertThat(requests).containsExactly(Duration.ofSeconds(10));
        assertThat(timer.nanoTime() - start).as("no sleep for a retry that cannot start").isEqualTo(Duration.ofSeconds(11).toNanos());

        // Exactly 13 s fit before the sleep, but the sleep overruns by 1 ms: measured again, the retry still does not start.
        requests.clear();
        timer.onSleep = () -> timer.advance(Duration.ofMillis(1));
        attempts = discord.testWebhook(WebhookUrl.parse("https://discord.com/api/webhooks/1/token").orElseThrow(), "{}".getBytes(UTF_8), timer.nanoTime());
        assertThat(attempts).extracting(a -> a.result().outcome()).containsExactly(SendOutcome.TIMEOUT);
        assertThat(requests).containsExactly(Duration.ofSeconds(10));
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
        assertThat(ledger.reserve(claims.getFirst(), UUID.randomUUID(), new byte[32], null, null, () -> Duration.ofSeconds(6)))
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
        Path bundle = Files.createFile(dir.resolve("bundle.log"));  // the dump's own journal: empty, nothing erased then
        // A path typo or a mount the restore missed, and a path that is a directory: failures, never "nothing to re-apply".
        for (Path wrong : List.of(file, dir)) {
            var command = reapply(new ErasureJournal(wrong), bundle);
            assertThatThrownBy(() -> command.run(null)).as(wrong.toString())
                    .isInstanceOf(ErasureJournal.CorruptJournal.class).hasMessage("ERASURE_LOG_MISSING");
        }
        Files.createFile(file);
        var journal = new ErasureJournal(file);
        journal.append(a.id(), clock.now);
        Files.writeString(file, "torn", StandardOpenOption.APPEND);
        var torn = reapply(journal, bundle);
        assertThatThrownBy(() -> torn.run(null)).isInstanceOf(ErasureJournal.CorruptJournal.class).hasMessage("ERASURE_LOG_TORN_LAST_LINE");
        assertThat(output).as("no completion is reported for a failed run").doesNotContain("ERASURE_REAPPLY_COMPLETE");
        assertThat(jdbc.sql("SELECT count(*) FROM subscription").query(Integer.class).single()).as("fail closed").isEqualTo(2);

        Files.writeString(file, "");
        assertThat(torn.reapply()).as("an existing empty journal: prepared, nothing erased yet").isZero();
        Files.writeString(file, a.id() + "\t" + clock.now + "\n" + b.id() + "\t" + clock.now + "\n");
        reapply(journal, bundle).run(null);
        assertThat(output).contains("ERASURE_COVERAGE_VERIFIED rule=LEGACY_PREFIX").contains("ERASURE_REAPPLY_COMPLETE deleted=2");
        assertThat(jdbc.sql("SELECT count(*) FROM subscription").query(Integer.class).single()).isZero();
    }

    @Test
    void theRestoreGateRunsBeforeAnyDeleteAndMarkersAreNeverDeleted(@TempDir Path dir, CapturedOutput output) throws Exception {
        Pending a = pending();
        Pending b = pending();
        Pending stand = pending();
        // A subscription row that happens to carry a marker id (only possible by hand): the marker line must not delete it.
        UUID marker = UUID.fromString("67636862-6172-8a1b-9c3d-4e5f60718293");
        jdbc.sql("DELETE FROM notification WHERE subscription_id = :id").param("id", stand.id()).update();
        jdbc.sql("UPDATE subscription SET id = :marker WHERE id = :id").param("marker", marker).param("id", stand.id()).update();
        String at = "\t" + clock.now + "\n";
        Path latest = dir.resolve("erasure.log");
        Path bundle = dir.resolve("bundle.log");
        var journal = new ErasureJournal(latest);

        // Missing B: nothing is deleted even though L is valid.
        Files.writeString(latest, a.id() + at + b.id() + at);
        assertThatThrownBy(() -> new ErasureReapplyCommand(journal, "", jdbc, manager, new GenericApplicationContext()).run(null))
                .isInstanceOf(ErasureJournal.CorruptJournal.class).hasMessage("ERASURE_BUNDLE_REQUIRED");
        assertThatThrownBy(() -> reapply(journal, bundle).reapply()).hasMessage("ERASURE_BUNDLE_MISSING");

        // A valid head in L but B not covered (B lists b first): the gate fails and no row is deleted.
        Files.writeString(bundle, b.id() + at);
        assertThatThrownBy(() -> reapply(journal, bundle).run(null))
                .isInstanceOf(ErasureJournal.CorruptJournal.class).hasMessage("ERASURE_COVERAGE_NOT_PREFIX");
        // A valid head in L and a malformed later line: no row is deleted either.
        Files.writeString(bundle, a.id() + at);
        Files.writeString(latest, a.id() + at + b.id() + "\t2026-02-30T00:00:00Z\n");
        assertThatThrownBy(() -> reapply(journal, bundle).run(null)).hasMessage("ERASURE_LOG_BAD_LINE_2");
        assertThat(output).doesNotContain("ERASURE_COVERAGE_VERIFIED").doesNotContain("ERASURE_REAPPLY_COMPLETE");
        assertThat(jdbc.sql("SELECT count(*) FROM subscription").query(Integer.class).single()).as("fail closed").isEqualTo(3);

        // S2: L starts with the marker the dump's B also holds. The marker is not a subscription to delete.
        Files.writeString(bundle, a.id() + at + marker + at + b.id() + at);
        Files.writeString(latest, marker + at + a.id() + at + b.id() + at);
        assertThatThrownBy(() -> reapply(journal, bundle).reapply()).as("B's tail after the marker is not a prefix of L")
                .hasMessage("ERASURE_COVERAGE_NOT_PREFIX");
        Files.writeString(bundle, b.id() + at + marker + at + a.id() + at);
        reapply(journal, bundle).run(null);
        assertThat(output).contains("ERASURE_COVERAGE_VERIFIED rule=MARKER_SUFFIX").contains("ERASURE_REAPPLY_COMPLETE deleted=2");
        assertThat(jdbc.sql("SELECT id FROM subscription").query(UUID.class).list()).containsExactly(marker);
        assertThat(reapply(journal, bundle).reapply()).as("a rerun deletes nothing more").isZero();
    }

    private ErasureReapplyCommand reapply(ErasureJournal journal, Path bundle) {
        return new ErasureReapplyCommand(journal, bundle.toString(), jdbc, manager, new GenericApplicationContext());
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
