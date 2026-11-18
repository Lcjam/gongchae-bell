package kr.gongchae.bell.alerts;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import javax.sql.DataSource;
import kr.gongchae.bell.IntegrationTestSupport;
import kr.gongchae.bell.ingestion.PostingIngestor;
import kr.gongchae.bell.ingestion.runtime.CollectionRuns;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * End-to-end #7 tests: matching/outbox, 08:00/09:00 KST batching & slot catch-up, two-worker SKIP LOCKED, crash
 * recovery, lock races with unsubscribe/update, midnight D-3 transitions, unknown/hash re-check, 404/429/5xx,
 * RFC 8058 round-trip, and Prometheus gauges/counters against Testcontainers PostgreSQL 17 and real fixtures.
 */
@Import(AlertDeliveryIntegrationTest.Fakes.class)
@TestPropertySource(properties = {
        "app.base-url=https://gongchae.example",
        "alerts.enabled=true",
        "alerts.dispatch.scheduler-enabled=false",
        "alerts.dispatch.worker-concurrency=2",
        "alerts.dispatch.batch-size=20",
        "alerts.webhook-key-id=k1",
        "alerts.mail.api-key=re_fixture_not_a_key",
        "alerts.mail.from=공채벨 <alerts@gongchae.example>",
        "alerts.privacy.officer=개인정보 담당(시험값)",
        "alerts.privacy.contact=시험 연락처",
        "alerts.privacy.policy-version=test-1",
        "alerts.privacy.effective-date=2026-11-18",
        "alerts.privacy.hosting-location=시험 위치",
        "alerts.privacy.overseas-basis=시험 근거",
        "alerts.privacy.resend-retention=시험 보관"
})
class AlertDeliveryIntegrationTest extends IntegrationTestSupport {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    static final Path JOURNAL;
    static final HttpServer HEARTBEAT_SERVER;
    static final AtomicInteger HEARTBEAT_PINGS = new AtomicInteger();
    static final AtomicInteger HEARTBEAT_STATUS = new AtomicInteger(200);

    static {
        try {
            JOURNAL = Files.createFile(Files.createTempDirectory("erasure-delivery").resolve("erasure.log"));
            HEARTBEAT_SERVER = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            HEARTBEAT_SERVER.createContext("/digest", exchange -> {
                HEARTBEAT_PINGS.incrementAndGet();
                exchange.sendResponseHeaders(HEARTBEAT_STATUS.get(), -1);
                exchange.close();
            });
            HEARTBEAT_SERVER.start();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static final AtomicReference<Instant> NOW = new AtomicReference<>(Instant.parse("2026-11-18T00:00:00Z")); // 09:00 KST
    static final List<SentMail> SENT_MAILS = new CopyOnWriteArrayList<>();
    static final List<SentWebhook> SENT_WEBHOOKS = new CopyOnWriteArrayList<>();
    static final Deque<SendOutcome.Result> MAIL_SCRIPT = new ArrayDeque<>();
    static final Deque<SendOutcome.Result> WEBHOOK_SCRIPT = new ArrayDeque<>();
    static final AtomicReference<Runnable> ON_MAIL_CALL = new AtomicReference<>(() -> {});
    static final AtomicReference<Runnable> ON_WEBHOOK_CALL = new AtomicReference<>(() -> {});

    record SentMail(String body, UUID key) {}
    record SentWebhook(WebhookUrl url, String body) {}

    @DynamicPropertySource
    static void dynamicProps(DynamicPropertyRegistry registry) {
        registry.add("alerts.erasure-log", JOURNAL::toString);
        registry.add("alerts.token-key", () -> AlertsSecurityTest.KEY_A);
        registry.add("alerts.hmac-key", () -> AlertsSecurityTest.KEY_B);
        registry.add("alerts.webhook-key", () -> AlertsSecurityTest.KEY_C);
        registry.add("heartbeat.digest-url",
                () -> "http://127.0.0.1:" + HEARTBEAT_SERVER.getAddress().getPort() + "/digest");
    }

    @TestConfiguration
    static class Fakes {
        @Bean @Primary Clock testClock() {
            return new Clock() {
                @Override public ZoneId getZone() { return KST; }
                @Override public Clock withZone(ZoneId zone) { return this; }
                @Override public Instant instant() { return NOW.get(); }
            };
        }

        @Bean @Primary MailTransport fakeMail() {
            return (payload, key, timeout) -> {
                ON_MAIL_CALL.get().run();
                SENT_MAILS.add(new SentMail(new String(payload, UTF_8), key));
                synchronized (MAIL_SCRIPT) {
                    return MAIL_SCRIPT.isEmpty()
                            ? SendOutcome.Result.of(SendOutcome.SUCCESS) : MAIL_SCRIPT.removeFirst();
                }
            };
        }

        @Bean @Primary WebhookTransport fakeWebhook() {
            return (url, payload, timeout) -> {
                ON_WEBHOOK_CALL.get().run();
                SENT_WEBHOOKS.add(new SentWebhook(url, new String(payload, UTF_8)));
                synchronized (WEBHOOK_SCRIPT) {
                    return WEBHOOK_SCRIPT.isEmpty()
                            ? SendOutcome.Result.of(SendOutcome.SUCCESS) : WEBHOOK_SCRIPT.removeFirst();
                }
            };
        }
    }

    @Autowired JdbcClient jdbc;
    @Autowired DataSource dataSource;
    @Autowired MockMvc mvc;
    @Autowired AlertsCrypto crypto;
    @Autowired PostingIngestor ingestor;
    @Autowired CollectionRuns runs;
    @Autowired AlertBatchGenerator batchGenerator;
    @Autowired AlertDispatcher dispatcher;
    @Autowired AlertWorker worker;
    @Autowired DeliveryLedger ledger;
    @Autowired RateLimiter limits;
    @Autowired SubscriptionService subscriptions;
    @Value("${local.management.port}") int managementPort;

    @BeforeEach
    @AfterEach
    void resetState() throws IOException {
        NOW.set(Instant.parse("2026-11-18T00:00:00Z")); // 2026-11-18 09:00 KST
        SENT_MAILS.clear();
        SENT_WEBHOOKS.clear();
        synchronized (MAIL_SCRIPT) { MAIL_SCRIPT.clear(); }
        synchronized (WEBHOOK_SCRIPT) { WEBHOOK_SCRIPT.clear(); }
        ON_MAIL_CALL.set(() -> {});
        ON_WEBHOOK_CALL.set(() -> {});
        HEARTBEAT_PINGS.set(0);
        HEARTBEAT_STATUS.set(200);
        Files.writeString(JOURNAL, "", UTF_8);
        jdbc.sql("""
                TRUNCATE notification_item, notification_attempt, notification, rate_limit, subscription,
                         posting_change, posting_code, posting, code_name, institution, collect_run RESTART IDENTITY CASCADE
                """).update();
    }

    // ---- helpers ----

    private ObjectNode loadSampleRoot() {
        try {
            JsonNode raw = JSON.readTree(Files.readString(Path.of("fixtures/alio-public-list-sample.json")));
            return ((ObjectNode) raw.get("data")).deepCopy();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private String fixtureBody(long onlySn, Consumer<ObjectNode> edit) {
        ObjectNode root = loadSampleRoot();
        ArrayNode rows = (ArrayNode) root.get("result");
        ObjectNode picked = null;
        for (JsonNode row : rows) {
            if (row.path("recrutPblntSn").asLong() == onlySn) {
                picked = ((ObjectNode) row).deepCopy();
                break;
            }
        }
        if (picked == null) throw new IllegalArgumentException("missing sample sn " + onlySn);
        edit.accept(picked);
        ArrayNode next = JSON.createArrayNode().add(picked);
        root.set("result", next);
        root.put("totalCount", 1);
        return JSON.writeValueAsString(root);
    }

    private String multiFixtureBody(Consumer<ArrayNode> mutator) {
        ObjectNode root = loadSampleRoot();
        ArrayNode rows = (ArrayNode) root.get("result");
        mutator.accept(rows);
        root.put("totalCount", rows.size());
        return JSON.writeValueAsString(root);
    }

    private UUID insertActiveEmail(String email, String ncs, String region, String career, String q,
            boolean notifyNew, boolean notifyDeadline, Instant confirmedAt) {
        UUID id = UUID.randomUUID();
        String cond = "{\"ncs\":" + jsonStr(ncs) + ",\"region\":" + jsonStr(region)
                + ",\"hire\":null,\"career\":" + jsonStr(career) + ",\"q\":" + jsonStr(q) + "}";
        OffsetDateTime at = confirmedAt.atOffset(ZoneOffset.UTC);
        jdbc.sql("""
                INSERT INTO subscription(id, channel, status, email, dest_hmac, conditions, notify_new, notify_deadline,
                  consent_version, consented_at, age_confirmed, confirmed_at, created_at, updated_at)
                VALUES (:id, 'EMAIL', 'ACTIVE', :email, :dest, CAST(:cond AS jsonb), :n, :d, 'test-1', :at, true, :at, :at, :at)
                """).param("id", id).param("email", email).param("dest", crypto.destination("EMAIL", email))
                .param("cond", cond).param("n", notifyNew).param("d", notifyDeadline).param("at", at).update();
        return id;
    }

    private UUID insertActiveDiscord(String webhookUrl, String ncs, String region, String career, String q,
            boolean notifyNew, boolean notifyDeadline, Instant confirmedAt) {
        UUID id = UUID.randomUUID();
        String cond = "{\"ncs\":" + jsonStr(ncs) + ",\"region\":" + jsonStr(region)
                + ",\"hire\":null,\"career\":" + jsonStr(career) + ",\"q\":" + jsonStr(q) + "}";
        OffsetDateTime at = confirmedAt.atOffset(ZoneOffset.UTC);
        jdbc.sql("""
                INSERT INTO subscription(id, channel, status, webhook_enc, webhook_key_id, dest_hmac, conditions,
                  notify_new, notify_deadline, consent_version, consented_at, age_confirmed, confirmed_at, created_at, updated_at)
                VALUES (:id, 'DISCORD', 'ACTIVE', :enc, :keyId, :dest, CAST(:cond AS jsonb), :n, :d, 'test-1', :at, true, :at, :at, :at)
                """).param("id", id).param("enc", crypto.encryptWebhook(id, webhookUrl)).param("keyId", crypto.webhookKeyId())
                .param("dest", crypto.destination("DISCORD", webhookUrl)).param("cond", cond)
                .param("n", notifyNew).param("d", notifyDeadline).param("at", at).update();
        return id;
    }

    private static String jsonStr(String s) {
        return s == null ? "null" : "\"" + s + "\"";
    }

    // ---- 1. Outbox filtering (regular only, OPEN only, backfill/update/CHECK/reappear excluded, rollback atomic) ----

    @Test
    void regularOutboxRecordsOnlyNewOpenPostingsExcludesBackfillCheckClosedReappearAndRollsBackAtomically() {
        Instant subConfirmedAt = Instant.parse("2026-11-18T00:00:00Z");
        UUID discordSub = insertActiveDiscord("https://discord.com/api/webhooks/100/tok-a",
                null, null, null, null, true, false, subConfirmedAt);

        // (a) Backfill upsert -> NEVER creates DISCORD_NEW
        String backfillBody = fixtureBody(307032L, r -> {
            r.put("pbancBgngYmd", "20261115");
            r.put("pbancEndYmd", "20261125");
            r.put("ongoingYn", "Y");
        });
        ingestor.upsertPage(ingestor.parsePage(backfillBody), Instant.parse("2026-11-18T00:10:00Z"));
        assertThat(jdbc.sql("SELECT count(*) FROM notification").query(Integer.class).single()).isZero();

        // (b) Regular collection:
        //  - 307032 (already inserted via backfill) updated -> excluded
        //  - 307031 newly inserted CLOSED (ongoingYn=N) -> excluded
        //  - 307030 newly inserted CLOSED (past endDate) -> excluded
        //  - 307029 newly inserted OPEN (endDate >= kstToday) -> included!
        String regularBody = multiFixtureBody(rows -> {
            while (rows.size() > 4) rows.remove(rows.size() - 1);
            ((ObjectNode) rows.get(0)).put("recrutPblntSn", 307032L).put("pbancEndYmd", "20261126").put("ongoingYn", "Y");
            ((ObjectNode) rows.get(1)).put("recrutPblntSn", 307031L).put("pbancEndYmd", "20261125").put("ongoingYn", "N");
            ((ObjectNode) rows.get(2)).put("recrutPblntSn", 307030L).put("pbancEndYmd", "20261117").put("ongoingYn", "Y");
            ((ObjectNode) rows.get(3)).put("recrutPblntSn", 307029L).put("pbancEndYmd", "20261125").put("ongoingYn", "Y");
        });
        long run1 = runs.claim("REGULAR", "2026-11-18T10:00:00+09:00").orElseThrow();
        ingestor.applyRegular(run1, regularBody, Instant.parse("2026-11-18T01:00:00Z"));

        assertThat(jdbc.sql("SELECT count(*) FROM notification WHERE kind = 'DISCORD_NEW'").query(Integer.class).single())
                .isEqualTo(1);
        assertThat(jdbc.sql("SELECT sn FROM notification_item WHERE subscription_id = :sub AND kind = 'NEW'")
                .param("sub", discordSub).query(Long.class).list()).containsExactly(307029L);

        // (c) Re-ingest dropping 307029 (becomes CHECK), then re-appearing as OPEN -> NO new DISCORD_NEW!
        String absentBody = fixtureBody(307032L, r -> r.put("pbancEndYmd", "20261126").put("ongoingYn", "Y"));
        long run2 = runs.claim("REGULAR", "2026-11-18T11:00:00+09:00").orElseThrow();
        ingestor.applyRegular(run2, absentBody, Instant.parse("2026-11-18T02:00:00Z"));
        assertThat(jdbc.sql("SELECT status FROM posting WHERE sn = 307029").query(String.class).single()).isEqualTo("CHECK");

        String reappearBody = multiFixtureBody(rows -> {
            while (rows.size() > 2) rows.remove(rows.size() - 1);
            ((ObjectNode) rows.get(0)).put("recrutPblntSn", 307032L).put("pbancEndYmd", "20261126").put("ongoingYn", "Y");
            ((ObjectNode) rows.get(1)).put("recrutPblntSn", 307029L).put("pbancEndYmd", "20261125").put("ongoingYn", "Y");
        });
        long run3 = runs.claim("REGULAR", "2026-11-18T12:00:00+09:00").orElseThrow();
        ingestor.applyRegular(run3, reappearBody, Instant.parse("2026-11-18T03:00:00Z"));
        assertThat(jdbc.sql("SELECT count(*) FROM notification WHERE kind = 'DISCORD_NEW'").query(Integer.class).single())
                .isEqualTo(1);

        // (d) Regular TX rollback: force collect_run already-finished before applyRegular finishes -> posting & outbox rollback together
        long run4 = runs.claim("REGULAR", "2026-11-18T13:00:00+09:00").orElseThrow();
        jdbc.sql("UPDATE collect_run SET result = 'FAILED', finished_at = now() WHERE id = :id").param("id", run4).update();
        // Bypass requireOpenRun by flipping back to NULL just inside a trigger? Even simpler: flip result after requireOpenRun?
        // Instead verify via IllegalStateException: pass a run with deleted/finished check AFTER write by setting result before finish?
        // Re-open run4, then add temporary constraint? Simplest: make batch_key conflict impossible? No: check posting rollback when finish() fails!
        jdbc.sql("UPDATE collect_run SET result = NULL, finished_at = NULL WHERE id = :id").param("id", run4).update();
        // Corrupt a future SN? No, force FK failure or statement inside write? Actually use a CHECK-violating slot length (> 64 chars -> batch_key varchar(64) overflow in outbox or finish)!
        long runRollback = runs.claim("REGULAR", "slot-overflow-" + "x".repeat(55)).orElseThrow(); // 69 chars > notification.batch_key(64)
        String rollbackBody = fixtureBody(307028L, r -> r.put("pbancEndYmd", "20261125").put("ongoingYn", "Y"));
        assertThatThrownBy(() -> ingestor.applyRegular(runRollback, rollbackBody, Instant.parse("2026-11-18T04:00:00Z")))
                .isInstanceOf(RuntimeException.class);
        assertThat(jdbc.sql("SELECT count(*) FROM posting WHERE sn = 307028").query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM notification WHERE kind = 'DISCORD_NEW'").query(Integer.class).single())
                .isEqualTo(1);
    }

    // ---- 2. 08:00/09:00 KST digest/deadline, two-app slot race, same-day catch-up, NEW+D-3 combined & heartbeat ----

    @Test
    void dailyDigestCombinesNewAndD3ClaimsSlotOnceAcrossAppsAndSendsResilientHeartbeat() throws Exception {
        // Subscription confirmed at 07:00 KST on 2026-11-18
        Instant tConfirm = Instant.parse("2026-11-17T22:00:00Z"); // 2026-11-18 07:00 KST
        UUID emailSub = insertActiveEmail("digest@example.com", null, null, null, null, true, true, tConfirm);
        UUID discordSub = insertActiveDiscord("https://discord.com/api/webhooks/200/tok-d",
                null, null, null, null, true, true, tConfirm);

        // Before 08:00 KST -> neither digest nor deadline runs
        NOW.set(Instant.parse("2026-11-17T22:59:00Z")); // 07:59 KST
        assertThat(batchGenerator.runDigestIfDue(NOW.get())).isFalse();
        assertThat(batchGenerator.runDeadlineIfDue(NOW.get())).isFalse();

        // Seed postings:
        // - Backfill OPEN D-3 (2026-11-21) -> eligible for DEADLINE ONLY (not NEW!)
        String backfillD3 = fixtureBody(307032L, r -> {
            r.put("pbancEndYmd", "20261121");
            r.put("ongoingYn", "Y");
        });
        ingestor.upsertPage(ingestor.parsePage(backfillD3), Instant.parse("2026-11-18T00:00:00Z"));
        // - Regular OPEN D-10 (2026-11-28) -> eligible for EMAIL NEW
        // - Regular CHECK D-3 (should NEVER enter D-3 deadline!)
        // - Regular OPEN D-3 (2026-11-21) -> eligible for BOTH NEW and DEADLINE on same sn!
        String regularBody = multiFixtureBody(rows -> {
            while (rows.size() > 4) rows.remove(rows.size() - 1);
            ((ObjectNode) rows.get(0)).put("recrutPblntSn", 307032L).put("pbancEndYmd", "20261121").put("ongoingYn", "Y"); // keep backfill OPEN
            ((ObjectNode) rows.get(1)).put("recrutPblntSn", 307031L).put("pbancEndYmd", "20261128").put("ongoingYn", "Y");
            ((ObjectNode) rows.get(2)).put("recrutPblntSn", 307030L).put("pbancEndYmd", "20261121").put("ongoingYn", "Y");
            ((ObjectNode) rows.get(3)).put("recrutPblntSn", 307029L).put("pbancEndYmd", "20261121").put("ongoingYn", "Y");
        });
        long regRun = runs.claim("REGULAR", "2026-11-18T07:30:00+09:00").orElseThrow();
        ingestor.applyRegular(regRun, regularBody, Instant.parse("2026-11-17T22:30:00Z")); // 07:30 KST
        jdbc.sql("UPDATE posting SET status = 'CHECK' WHERE sn = 307029").update();

        // Pre-insert a synthetic EMAIL_CONFIRM sync row to verify worker never touches sync kinds
        ledger.createSync(emailSub, "EMAIL_CONFIRM");

        // 08:15 KST catch-up: two concurrent apps race DIGEST slot! Also make heartbeat endpoint fail 500 to verify resilience.
        Instant at0815Kst = Instant.parse("2026-11-17T23:15:00Z"); // 2026-11-18 08:15 KST
        NOW.set(at0815Kst);
        HEARTBEAT_STATUS.set(500);
        CountDownLatch gate = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<Boolean> a = pool.submit(() -> { gate.await(); return batchGenerator.runDigestIfDue(at0815Kst); });
            Future<Boolean> b = pool.submit(() -> { gate.await(); return batchGenerator.runDigestIfDue(at0815Kst); });
            gate.countDown();
            assertThat(List.of(a.get(5, TimeUnit.SECONDS), b.get(5, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        assertThat(HEARTBEAT_PINGS).hasValue(1);
        assertThat(jdbc.sql("SELECT count(*) FROM notification WHERE subscription_id = :sub AND kind = 'EMAIL_DIGEST'")
                .param("sub", emailSub).query(Integer.class).single()).isEqualTo(1);
        // Same SN 307030 has BOTH NEW and DEADLINE; 307032 backfill has DEADLINE only; 307031 has NEW only; 307029 CHECK excluded
        assertThat(jdbc.sql("""
                SELECT sn, kind FROM notification_item WHERE subscription_id = :sub ORDER BY kind, sn
                """).param("sub", emailSub).query((rs, row) -> rs.getLong(1) + ":" + rs.getString(2)).list())
                .containsExactly("307030:DEADLINE", "307032:DEADLINE", "307030:NEW", "307031:NEW");

        // 09:05 KST catch-up: two apps race DEADLINE slot
        Instant at0905Kst = Instant.parse("2026-11-18T00:05:00Z"); // 2026-11-18 09:05 KST
        NOW.set(at0905Kst);
        CountDownLatch dlGate = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<Boolean> a = pool.submit(() -> { dlGate.await(); return batchGenerator.runDeadlineIfDue(at0905Kst); });
            Future<Boolean> b = pool.submit(() -> { dlGate.await(); return batchGenerator.runDeadlineIfDue(at0905Kst); });
            dlGate.countDown();
            assertThat(List.of(a.get(5, TimeUnit.SECONDS), b.get(5, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        // Dispatch all due async jobs; sync EMAIL_CONFIRM stays untouched!
        worker.runOnce();
        assertThat(SENT_MAILS).hasSize(1);
        assertThat(SENT_MAILS.getFirst().body()).contains("[마감 임박 D-3 · 2건]").contains("[새 공고 · 2건]");
        assertThat(SENT_WEBHOOKS).hasSize(2); // DISCORD_NEW (from regRun) + DISCORD_DEADLINE (from 09:05)
        assertThat(jdbc.sql("SELECT status FROM notification WHERE kind = 'EMAIL_CONFIRM'")
                .query(String.class).single()).isEqualTo("SENDING");

        // Simulate 30d purge leaving notification_id = NULL on notification_item -> next day digest does NOT resend them!
        jdbc.sql("UPDATE notification_item SET notification_id = NULL WHERE subscription_id = :sub").param("sub", emailSub).update();
        assertThat(batchGenerator.generateEmailDigest(LocalDate.of(2026, 11, 19))).isZero();
    }

    // ---- 3. Two-worker claim + recovery fence + crash after R + unknown body/token_version/target change ----

    @Test
    void twoWorkersDoNotDuplicateCrashRecoveryFencesStaleWorkerAndHandlesUnknownChangesAndFailedReturn() throws Exception {
        Instant tConfirm = Instant.parse("2026-11-17T22:00:00Z");
        UUID sub1 = insertActiveEmail("w1@example.com", null, null, null, null, true, true, tConfirm);
        UUID sub2 = insertActiveEmail("w2@example.com", null, null, null, null, true, true, tConfirm);
        String reg = multiFixtureBody(rows -> {
            while (rows.size() > 2) rows.remove(rows.size() - 1);
            ((ObjectNode) rows.get(0)).put("recrutPblntSn", 307032L).put("pbancEndYmd", "20261121").put("ongoingYn", "Y");
            ((ObjectNode) rows.get(1)).put("recrutPblntSn", 307031L).put("pbancEndYmd", "20261128").put("ongoingYn", "Y");
        });
        long regId = runs.claim("REGULAR", "2026-11-18T07:30:00+09:00").orElseThrow();
        ingestor.applyRegular(regId, reg, Instant.parse("2026-11-17T22:30:00Z"));
        batchGenerator.generateEmailDigest(LocalDate.of(2026, 11, 18));

        // Concurrent claimDue across 2 threads -> disjoint claims, 2 sent
        CountDownLatch claimGate = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<List<DeliveryLedger.Claim>> f1 = pool.submit(() -> { claimGate.await(); return ledger.claimDue(1); });
            Future<List<DeliveryLedger.Claim>> f2 = pool.submit(() -> { claimGate.await(); return ledger.claimDue(1); });
            claimGate.countDown();
            List<DeliveryLedger.Claim> c1 = f1.get(5, TimeUnit.SECONDS);
            List<DeliveryLedger.Claim> c2 = f2.get(5, TimeUnit.SECONDS);
            assertThat(c1).hasSize(1);
            assertThat(c2).hasSize(1);
            assertThat(c1.getFirst().id()).isNotEqualTo(c2.getFirst().id());

            // Crash after claim (c1) > 5m -> recoverAsync reclaims c1; stale c1 dispatcher call sends NOTHING!
            DeliveryLedger.Claim staleClaim = c1.getFirst();
            NOW.set(NOW.get().plus(Duration.ofMinutes(6)));
            assertThat(ledger.recoverAsync()).isEqualTo(2); // both c1 and c2 recovered to RETRY
            dispatcher.dispatchClaim(staleClaim);
            assertThat(SENT_MAILS).isEmpty();
        }

        // Case A: sub1 gets 5xx (UNKNOWN) on attempt 1, then posting title changes before attempt 2 -> UNCERTAIN BODY_CHANGED, items KEPT!
        synchronized (MAIL_SCRIPT) {
            MAIL_SCRIPT.addLast(SendOutcome.Result.of(SendOutcome.HTTP_5XX)); // sub1 attempt 1 -> RETRY
            MAIL_SCRIPT.addLast(SendOutcome.Result.of(SendOutcome.HTTP_5XX)); // sub2 attempt 1 -> RETRY
        }
        worker.runOnce();
        assertThat(SENT_MAILS).hasSize(2);
        UUID sub1KeyAfterUnknown = SENT_MAILS.get(0).key();

        // Mutate posting title for sub1 check & bump token_version for sub2
        jdbc.sql("UPDATE posting SET title = '변경된 공고 제목' WHERE sn = 307032").update();
        jdbc.sql("UPDATE subscription SET token_version = token_version + 1 WHERE id = :id").param("id", sub2).update();
        NOW.set(NOW.get().plus(Duration.ofSeconds(5)));
        SENT_MAILS.clear();
        worker.runOnce();
        // Neither sub1 nor sub2 sent a second HTTP! Both ended UNCERTAIN with BODY_CHANGED and preserved items!
        assertThat(SENT_MAILS).isEmpty();
        assertThat(jdbc.sql("SELECT status, last_error, attempts FROM notification WHERE subscription_id = :s AND kind = 'EMAIL_DIGEST'")
                .param("s", sub1).query((rs, r) -> rs.getString(1) + "|" + rs.getString(2) + "|" + rs.getInt(3)).single())
                .isEqualTo("UNCERTAIN|BODY_CHANGED|1");
        assertThat(jdbc.sql("SELECT status, last_error, attempts FROM notification WHERE subscription_id = :s AND kind = 'EMAIL_DIGEST'")
                .param("s", sub2).query((rs, r) -> rs.getString(1) + "|" + rs.getString(2) + "|" + rs.getInt(3)).single())
                .isEqualTo("UNCERTAIN|BODY_CHANGED|1");
        assertThat(jdbc.sql("SELECT count(*) FROM notification_item WHERE subscription_id IN (:s1, :s2)")
                .param("s1", sub1).param("s2", sub2).query(Integer.class).single()).isEqualTo(6);

        // Case B: crash after R (A rolls back with attempt NULL, SEND_TOTAL kept) -> recoverAsync -> retry same key -> SENT (duplicate risk)
        UUID sub3 = insertActiveEmail("w3@example.com", null, null, null, null, true, true, tConfirm);
        LocalDate d3Day = LocalDate.of(2026, 11, 20);
        NOW.set(Instant.parse("2026-11-20T00:00:00Z")); // 2026-11-20 09:00 KST
        jdbc.sql("UPDATE posting SET end_date = '2026-11-23', status = 'OPEN' WHERE sn = 307032").update();
        jdbc.sql("UPDATE posting SET title = '원래 제목', end_date = '2026-11-28', status = 'OPEN' WHERE sn = 307031").update();
        assertThat(batchGenerator.generateEmailDigest(d3Day)).isEqualTo(1);
        byte[] emailTotalKey = crypto.limitKey("SEND_TOTAL", "email");
        int usedBeforeCrash = limits.count(RateLimiter.Scope.SEND_TOTAL, emailTotalKey, RateLimiter.EMAIL_TOTAL);

        // Simulate crash right after R inside TX2 (A rolls back, R stays committed with NULL attempt)
        ON_MAIL_CALL.set(() -> { throw new RuntimeException("SIMULATED_CRASH_AFTER_R"); });
        worker.runOnce();
        assertThat(SENT_MAILS).isEmpty();
        assertThat(limits.count(RateLimiter.Scope.SEND_TOTAL, emailTotalKey, RateLimiter.EMAIL_TOTAL))
                .isEqualTo(usedBeforeCrash + 1);
        UUID keyCommittedInR = jdbc.sql("SELECT idempotency_key FROM notification WHERE subscription_id = :s AND kind = 'EMAIL_DIGEST'")
                .param("s", sub3).query(UUID.class).single();
        assertThat(jdbc.sql("SELECT COALESCE(outcome, 'NULL') FROM notification_attempt a JOIN notification n ON n.id = a.notification_id WHERE n.subscription_id = :s")
                .param("s", sub3).query(String.class).single()).isEqualTo("NULL");

        // 6m later recoverAsync marks attempt UNKNOWN_CRASH -> retry reuses exact keyCommittedInR -> SENT!
        ON_MAIL_CALL.set(() -> {});
        NOW.set(NOW.get().plus(Duration.ofMinutes(6)));
        assertThat(ledger.recoverAsync()).isEqualTo(1);
        worker.runOnce();
        assertThat(SENT_MAILS).hasSize(1);
        assertThat(SENT_MAILS.getFirst().key()).isEqualTo(keyCommittedInR);

        // Case C: UNKNOWN + same-SN DEADLINE dropped (while NEW remains) -> UNCERTAIN TARGET_CHANGED, items KEPT
        UUID sub4 = insertActiveEmail("w4@example.com", null, null, null, null, true, true, tConfirm);
        LocalDate d4Day = LocalDate.of(2026, 11, 21);
        NOW.set(Instant.parse("2026-11-21T00:00:00Z"));
        jdbc.sql("UPDATE posting SET end_date = '2026-11-24', status = 'OPEN', first_seen_at = '2026-11-21T00:00:00Z' WHERE sn = 307032").update();
        assertThat(batchGenerator.generateEmailDigest(d4Day)).isEqualTo(1);
        synchronized (MAIL_SCRIPT) { MAIL_SCRIPT.addLast(SendOutcome.Result.of(SendOutcome.TIMEOUT)); }
        worker.runOnce();
        // Flip 307032 end_date to D+10 -> DEADLINE drops while NEW stays eligible -> TARGET_CHANGED!
        jdbc.sql("UPDATE posting SET end_date = '2026-12-01' WHERE sn = 307032").update();
        SENT_MAILS.clear();
        NOW.set(NOW.get().plus(Duration.ofSeconds(5)));
        worker.runOnce();
        assertThat(SENT_MAILS).isEmpty();
        assertThat(jdbc.sql("SELECT status, last_error FROM notification WHERE subscription_id = :s AND kind = 'EMAIL_DIGEST'")
                .param("s", sub4).query((rs, r) -> rs.getString(1) + "|" + rs.getString(2)).single())
                .isEqualTo("UNCERTAIN|TARGET_CHANGED");
        assertThat(jdbc.sql("SELECT count(*) FROM notification_item WHERE subscription_id = :s").param("s", sub4)
                .query(Integer.class).single()).isEqualTo(3); // 307032(NEW+DEADLINE) + 307031(NEW) all preserved

        // Case D: 3x definite failure (CONNECT_FAILED) uses fresh UUID each round, ends FAILED & RETURNS items!
        UUID sub5 = insertActiveEmail("w5@example.com", null, null, null, null, true, false, tConfirm);
        LocalDate d5Day = LocalDate.of(2026, 11, 22);
        NOW.set(Instant.parse("2026-11-22T00:00:00Z"));
        jdbc.sql("UPDATE posting SET end_date = '2026-11-30', first_seen_at = '2026-11-22T00:00:00Z' WHERE sn = 307032").update();
        assertThat(batchGenerator.generateEmailDigest(d5Day)).isEqualTo(1);
        synchronized (MAIL_SCRIPT) {
            MAIL_SCRIPT.addLast(SendOutcome.Result.of(SendOutcome.CONNECT_FAILED));
            MAIL_SCRIPT.addLast(SendOutcome.Result.of(SendOutcome.CONNECT_FAILED));
            MAIL_SCRIPT.addLast(SendOutcome.Result.of(SendOutcome.CONNECT_FAILED));
        }
        SENT_MAILS.clear();
        for (int attempt = 0; attempt < 3; attempt++) {
            worker.runOnce();
            NOW.set(NOW.get().plus(Duration.ofSeconds(10)));
        }
        assertThat(SENT_MAILS).hasSize(3);
        assertThat(SENT_MAILS.stream().map(SentMail::key).distinct().count()).isEqualTo(3);
        assertThat(jdbc.sql("SELECT status, last_error FROM notification WHERE subscription_id = :s AND kind = 'EMAIL_DIGEST'")
                .param("s", sub5).query((rs, r) -> rs.getString(1) + "|" + rs.getString(2)).single())
                .isEqualTo("FAILED|ATTEMPTS_EXHAUSTED");
        assertThat(jdbc.sql("SELECT count(*) FROM notification_item WHERE subscription_id = :s").param("s", sub5)
                .query(Integer.class).single()).isZero(); // items returned!

        // Case E: claim crash before R + > 23h elapsed -> recoverAsync ends FAILED & returns items
        LocalDate d6Day = LocalDate.of(2026, 11, 23);
        NOW.set(Instant.parse("2026-11-23T00:00:00Z"));
        assertThat(batchGenerator.generateEmailDigest(d6Day)).isEqualTo(1);
        assertThat(ledger.claimDue(5)).hasSize(1);
        NOW.set(NOW.get().plus(Duration.ofHours(24)));
        assertThat(ledger.recoverAsync()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT status, last_error FROM notification WHERE subscription_id = :s AND batch_key = '2026-11-23'")
                .param("s", sub5).query((rs, r) -> rs.getString(1) + "|" + rs.getString(2)).single())
                .isEqualTo("FAILED|EXPIRED");
        assertThat(jdbc.sql("SELECT count(*) FROM notification_item WHERE subscription_id = :s").param("s", sub5)
                .query(Integer.class).single()).isZero();
    }

    // ---- 4. Discord 404 (TX3 + manage reason), 429 retry_after, 5xx/401 active, QUOTA_DAILY/SEND_CAP/23h, midnight R ----

    @Test
    void handles404Disable429RetryAfterQuotaDailySendCapAndPostRMidnightBoundary() throws Exception {
        Instant tConfirm = Instant.parse("2026-11-17T22:00:00Z");
        UUID d404 = insertActiveDiscord("https://discord.com/api/webhooks/404/tok", null, null, null, null, true, true, tConfirm);
        UUID d429 = insertActiveDiscord("https://discord.com/api/webhooks/429/tok", null, null, null, null, false, true, tConfirm);
        UUID dMidnight = insertActiveDiscord("https://discord.com/api/webhooks/999/tok", null, null, null, null, false, true, tConfirm);

        // Posting with end_date = 2026-11-21 (D-3 on 2026-11-18)
        String regular = fixtureBody(307032L, r -> r.put("pbancEndYmd", "20261121").put("ongoingYn", "Y"));
        long regId = runs.claim("REGULAR", "2026-11-18T08:00:00+09:00").orElseThrow();
        ingestor.applyRegular(regId, regular, Instant.parse("2026-11-17T23:00:00Z"));
        // Make only d404 run DISCORD_NEW first, script 404 -> TX3 disables d404, manage page shows reason!
        synchronized (WEBHOOK_SCRIPT) { WEBHOOK_SCRIPT.addLast(new SendOutcome.Result(SendOutcome.WEBHOOK_404, 404, null)); }
        worker.runOnce();
        assertThat(jdbc.sql("SELECT status, disabled_reason FROM subscription WHERE id = :id").param("id", d404)
                .query((rs, r) -> rs.getString(1) + "|" + rs.getString(2)).single()).isEqualTo("DISABLED|WEBHOOK_404");
        String manageTok = crypto.capability(AlertsCrypto.Purpose.MANAGE, d404, 1);
        mvc.perform(get("/subscriptions/manage").param("t", manageTok))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("디스코드 웹훅 404")));

        // Run DISCORD_DEADLINE for 2026-11-18: d404 excluded (DISABLED), d429 & dMidnight created
        assertThat(batchGenerator.generateDiscordDeadline(LocalDate.of(2026, 11, 18))).isEqualTo(2);

        // Claim d429 and dMidnight separately to test 429 retry_after (d429) and midnight post-R drop (dMidnight)
        List<DeliveryLedger.Claim> claims = ledger.claimDue(10);
        assertThat(claims).hasSize(2);
        DeliveryLedger.Claim c429 = claims.stream()
                .filter(c -> ledger.loadAsyncJob(c.id()).orElseThrow().subscriptionId().equals(d429)).findFirst().orElseThrow();
        DeliveryLedger.Claim cMid = claims.stream()
                .filter(c -> ledger.loadAsyncJob(c.id()).orElseThrow().subscriptionId().equals(dMidnight)).findFirst().orElseThrow();

        // d429 gets 429 with retry_after = 15s -> RETRY at now + 15s; stays ACTIVE
        synchronized (WEBHOOK_SCRIPT) {
            WEBHOOK_SCRIPT.addLast(new SendOutcome.Result(SendOutcome.RATE_LIMITED, 429, Duration.ofSeconds(15)));
        }
        SENT_WEBHOOKS.clear();
        dispatcher.dispatchClaim(c429);
        assertThat(SENT_WEBHOOKS).hasSize(1);
        NOW.set(NOW.get().plus(Duration.ofSeconds(10))); // still < 15s -> claimDue skips d429
        assertThat(ledger.claimDue(5)).isEmpty();
        NOW.set(NOW.get().plus(Duration.ofSeconds(6))); // > 15s -> retry succeeds!
        worker.runOnce();
        assertThat(SENT_WEBHOOKS).hasSize(2);
        assertThat(jdbc.sql("SELECT status FROM subscription WHERE id = :id").param("id", d429).query(String.class).single())
                .isEqualTo("ACTIVE");

        // dMidnight: cross midnight during R (using TestDispatcher hook? Actually set NOW to 23:59:59 KST and advance inside SEND_TOTAL or run at 23:59:59 + advance clock during R!):
        // Wait: R runs before post-R check. We can test R-commit + post-R midnight drop cleanly by seeding a rate_limit trigger or moving NOW inside Transaction? Wait: no trigger needed — let's test QUOTA_DAILY and SEND_CAP first, AND advance NOW via a custom RateLimiter? Even simpler: insert at 23:59:59 KST, verify post-R recheck by simulating a clock advance hook via Thread? No — verify QUOTA_DAILY + SEND_CAP + same-SN partial drop (NEW kept, DEADLINE dropped)!
        // Wait: to deterministically test R -> midnight -> post-R drop WITHOUT touching production code:
        // Notice RateLimiter.acquireHere runs INSIDE R (connection B)! If we use a DB statement? No, JdbcClient calls clock.instant() in DeliveryLedger.now()!
        // Look at AlertDispatcher: nowAfterR = now() runs right AFTER lockReserved! While A doesn't call clock between before-R and R EXCEPT in DeliveryLedger.reserve!
        // Actually: wrap or inject? Or test partial same-SN drop before R + check QUOTA_DAILY / SEND_CAP!
        // Wait: let's test actual post-R midnight crossing cleanly! Can a PostgreSQL BEFORE INSERT ON notification_attempt trigger sleep? No, clock is Java `NOW`! What if `NOW` is bumped by a thread? No, even cleaner: in TestConfiguration we can wrap `MailTransport`? No, MailTransport is after post-R! Wait: `AlertDispatcher` calls `ledger.reserve` -> which calls `jdbc` and `clock.instant()` in `DeliveryLedger` (`now()`), so if a test `Clock` checks `StackWalker` or an `AtomicReference<Runnable> ON_RESERVE` (not needed in prod)! Actually `Clock.instant()` is a @Primary bean right inside THIS test class! When `ON_RESERVE_CLOCK_HOOK` triggers inside `DeliveryLedger.reserve` -> `now()` -> `clock.instant()`! ZERO production changes, 100% deterministic!
        Instant preMidnight = Instant.parse("2026-11-18T14:59:59Z"); // 2026-11-18 23:59:59 KST
        Instant postMidnight = Instant.parse("2026-11-18T15:00:01Z"); // 2026-11-19 00:00:01 KST (D-2!)
        NOW.set(preMidnight);
        byte[] discordTotalKey = crypto.limitKey("SEND_TOTAL", "discord");
        int discordUsedBefore = limits.count(RateLimiter.Scope.SEND_TOTAL, discordTotalKey, RateLimiter.DISCORD_TOTAL);
        // Set created_at on cMid back to 2026-11-18 09:00 KST so still within 23h; make clock jump to postMidnight when R inserts attempt!
        AtomicInteger clockReads = new AtomicInteger();
        // Wait: AlertDispatcher calls clock 1x before R (now()), then DeliveryLedger.reserve calls clock 1x for started_at!
        // Let's keep `testClock` supporting `ON_NEXT_ATTEMPT_CLOCK_STEP` or test explicitly:
        // Wait - look at Fakes.testClock above: we can add `static final AtomicReference<Instant> JUMP_ON_DELIVERY_RESERVE = new AtomicReference<>();`?
        // Actually in AlertDispatcher: `now()` is called in line 114 (pre-R), then `DeliveryLedger.reserve` calls `now()` in line 161 (during R), then `AlertDispatcher` calls `now()` in line 142 (post-R)!
        // Setting a simple `Deque<Instant> CLOCK_SCRIPT` on `testClock` is 3 lines in `AlertDeliveryIntegrationTest.Fakes` and 100% deterministic!
        // Let's check: pre-R reads `preMidnight`, R's `started_at` reads `preMidnight`, post-R `nowAfterR` reads `postMidnight`!
        synchronized (WEBHOOK_SCRIPT) { SENT_WEBHOOKS.clear(); }
        // (Will verify via CLOCK_SCRIPT in dedicated test method below!)
        dispatcher.dispatchClaim(cMid);
        assertThat(jdbc.sql("SELECT status FROM notification WHERE id = :id").param("id", cMid.id()).query(String.class).single())
                .isEqualTo("SENT");

        // Email QUOTA_DAILY after 09:05 KST -> next UTC 00:00 is > 23h -> EXPIRED immediately + items returned!
        UUID eQuota = insertActiveEmail("quota@example.com", null, null, null, null, true, false, tConfirm);
        Instant at0905Kst = Instant.parse("2026-11-18T00:05:00Z"); // 2026-11-18 09:05 KST
        NOW.set(at0905Kst);
        jdbc.sql("UPDATE posting SET status = 'OPEN', end_date = '2026-11-28', first_seen_at = '2026-11-18T00:01:00Z' WHERE sn = 307032").update();
        assertThat(batchGenerator.generateEmailDigest(LocalDate.of(2026, 11, 18))).isEqualTo(1);
        synchronized (MAIL_SCRIPT) { MAIL_SCRIPT.addLast(new SendOutcome.Result(SendOutcome.QUOTA_DAILY, 429, null)); }
        worker.runOnce();
        assertThat(jdbc.sql("SELECT status, last_error FROM notification WHERE subscription_id = :id AND kind = 'EMAIL_DIGEST'")
                .param("id", eQuota).query((rs, r) -> rs.getString(1) + "|" + rs.getString(2)).single())
                .isEqualTo("FAILED|EXPIRED");
        assertThat(jdbc.sql("SELECT status FROM subscription WHERE id = :id").param("id", eQuota).query(String.class).single())
                .isEqualTo("ACTIVE");

        // Resend 400 / 401 -> FAILED, subscription stays ACTIVE; SEND_CAP (90/day) -> 0 attempts, SEND_CAP retry before UTC+23h
        Instant at1000Kst = Instant.parse("2026-11-19T01:00:00Z"); // 2026-11-19 10:00 KST
        NOW.set(at1000Kst);
        assertThat(batchGenerator.generateEmailDigest(LocalDate.of(2026, 11, 19))).isEqualTo(1);
        synchronized (MAIL_SCRIPT) { MAIL_SCRIPT.addLast(new SendOutcome.Result(SendOutcome.REQUEST_4XX, 400, null)); }
        worker.runOnce();
        assertThat(jdbc.sql("SELECT status FROM subscription WHERE id = :id").param("id", eQuota).query(String.class).single())
                .isEqualTo("ACTIVE");

        // Fill SEND_TOTAL email ceiling (90) before 08:30 KST (23:30 UTC previous day -> next UTC midnight is only 30m away, within 23h!)
        Instant at0830Kst = Instant.parse("2026-11-19T23:30:00Z"); // 2026-11-20 08:30 KST = 2026-11-19 23:30 UTC
        NOW.set(at0830Kst);
        byte[] emailTotalKey = crypto.limitKey("SEND_TOTAL", "email");
        for (int i = 0; i < RateLimiter.EMAIL_TOTAL.max(); i++) {
            limits.acquire(RateLimiter.Scope.SEND_TOTAL, emailTotalKey, RateLimiter.EMAIL_TOTAL);
        }
        assertThat(batchGenerator.generateEmailDigest(LocalDate.of(2026, 11, 20))).isEqualTo(1);
        SENT_MAILS.clear();
        worker.runOnce();
        assertThat(SENT_MAILS).isEmpty();
        assertThat(jdbc.sql("SELECT status, last_error, attempts FROM notification WHERE subscription_id = :id AND batch_key = '2026-11-20'")
                .param("id", eQuota).query((rs, r) -> rs.getString(1) + "|" + rs.getString(2) + "|" + rs.getInt(3)).single())
                .isEqualTo("RETRY|SEND_CAP|0");
    }

    @Test
    void postRMidnightTransitionPreservesReservationBlocksHttpAndHandlesSameSnPartialDrop() {
        Instant tConfirm = Instant.parse("2026-11-17T22:00:00Z");
        UUID sub = insertActiveEmail("midnight@example.com", null, null, null, null, true, true, tConfirm);

        // Seed 1 posting with end_date = 2026-11-21 (both NEW and D-3 on 2026-11-18)
        String regular = fixtureBody(307032L, r -> r.put("pbancEndYmd", "20261121").put("ongoingYn", "Y"));
        long runId = runs.claim("REGULAR", "2026-11-18T07:30:00+09:00").orElseThrow();
        ingestor.applyRegular(runId, regular, Instant.parse("2026-11-17T22:30:00Z"));
        NOW.set(Instant.parse("2026-11-18T00:00:00Z"));
        batchGenerator.generateEmailDigest(LocalDate.of(2026, 11, 18));

        // Same-SN partial drop without midnight (e.g., end_date extended to 2026-11-30 before dispatch):
        // DEADLINE drops while NEW remains eligible -> sends NEW only and removes stale DEADLINE item!
        jdbc.sql("UPDATE posting SET end_date = '2026-11-30' WHERE sn = 307032").update();
        worker.runOnce();
        assertThat(SENT_MAILS).hasSize(1);
        assertThat(SENT_MAILS.getFirst().body()).contains("[새 공고 · 1건]").doesNotContain("[마감 임박 D-3");
        assertThat(jdbc.sql("SELECT kind FROM notification_item WHERE subscription_id = :s").param("s", sub).query(String.class).list())
                .containsExactly("NEW");

        // Now test Discord D-3 crossing midnight BETWEEN R and lockReserved check -> reservation NOT refunded, HTTP 0!
        UUID disc = insertActiveDiscord("https://discord.com/api/webhooks/777/mid", null, null, null, null, false, true, tConfirm);
        jdbc.sql("UPDATE posting SET end_date = '2026-11-21' WHERE sn = 307032").update();
        NOW.set(Instant.parse("2026-11-18T14:59:59Z")); // 2026-11-18 23:59:59 KST
        assertThat(batchGenerator.generateDiscordDeadline(LocalDate.of(2026, 11, 18))).isEqualTo(1);
        List<DeliveryLedger.Claim> dlClaims = ledger.claimDue(5);
        assertThat(dlClaims).hasSize(1);

        // Advance NOW by 2s on the 2nd clock read inside dispatchClaim (pre-R=23:59:59 -> R=23:59:59 -> post-R=00:00:01)
        // We achieve that cleanly by running a background check on jdbc? Even cleaner: test post-R check directly by overriding Clock or via transaction-time shift!
        // Let's verify using a dedicated AlertDispatcher constructed with a step-clock for this exact test!
        AtomicInteger reads = new AtomicInteger();
        Clock midnightSteppingClock = new Clock() {
            @Override public ZoneId getZone() { return KST; }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() {
                // read 1: AlertDispatcher pre-R; read 2: DeliveryLedger.reserve started_at; read 3+: post-R nowAfterR -> past midnight!
                return reads.incrementAndGet() <= 2
                        ? Instant.parse("2026-11-18T14:59:59Z")
                        : Instant.parse("2026-11-18T15:00:01Z");
            }
        };
        DeliveryLedger stepLedger = new DeliveryLedger(jdbc, midnightSteppingClock, limits,
                new org.springframework.jdbc.support.JdbcTransactionManager(dataSource));
        AlertDispatcher stepDispatcher = new AlertDispatcher(jdbc,
                new org.springframework.jdbc.support.JdbcTransactionManager(dataSource),
                stepLedger, new AlertMatcher(jdbc),
                new AlertPayloadBuilder(
                        new AlertsProperties(true, AlertsSecurityTest.KEY_A, AlertsSecurityTest.KEY_B,
                                AlertsSecurityTest.KEY_C, "k1", JOURNAL.toString(),
                                new AlertsProperties.Mail("re_fixture", "공채벨 <alerts@gongchae.example>", null),
                                new AlertsProperties.Privacy("o", "c", "v1", "2026-11-18", "h", "b", "r")),
                        crypto, "https://gongchae.example"),
                limits, crypto, (p, k, t) -> SendOutcome.Result.of(SendOutcome.SUCCESS),
                (u, p, t) -> { throw new AssertionError("HTTP must not be called after midnight D-2 drop!"); },
                new AlertsMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry(), jdbc, midnightSteppingClock,
                        new AlertsProperties(false, null, null, null, null, null, null, null), crypto, limits),
                midnightSteppingClock);

        byte[] discTotalKey = crypto.limitKey("SEND_TOTAL", "discord");
        int discUsedBefore = limits.count(RateLimiter.Scope.SEND_TOTAL, discTotalKey, RateLimiter.DISCORD_TOTAL);
        stepDispatcher.dispatchClaim(dlClaims.getFirst());
        assertThat(limits.count(RateLimiter.Scope.SEND_TOTAL, discTotalKey, RateLimiter.DISCORD_TOTAL))
                .isEqualTo(discUsedBefore + 1); // R reservation NOT refunded
        assertThat(jdbc.sql("SELECT status, last_error, attempts FROM notification WHERE id = :id")
                .param("id", dlClaims.getFirst().id())
                .query((rs, r) -> rs.getString(1) + "|" + rs.getString(2) + "|" + rs.getInt(3)).single())
                .isEqualTo("CANCELLED|NO_ITEMS|1");
        assertThat(jdbc.sql("SELECT outcome FROM notification_attempt WHERE notification_id = :id")
                .param("id", dlClaims.getFirst().id()).query(String.class).single())
                .isEqualTo("NOT_SENT");
    }

    // ---- 5. Unsubscribe / Update lock contention with TX2 (no deadlock, clean serialization) ----

    @Test
    void unsubscribeAndConditionUpdateSerializeWithTx2WithoutDeadlock() throws Exception {
        Instant tConfirm = Instant.parse("2026-11-17T22:00:00Z");
        UUID subUnsub = insertActiveEmail("race-unsub@example.com", null, null, null, null, true, false, tConfirm);
        UUID subEdit = insertActiveEmail("race-edit@example.com", null, null, null, null, true, false, tConfirm);
        String regular = fixtureBody(307032L, r -> r.put("ncsCdLst", "R600006").put("pbancEndYmd", "20261125").put("ongoingYn", "Y"));
        long regId = runs.claim("REGULAR", "2026-11-18T07:30:00+09:00").orElseThrow();
        ingestor.applyRegular(regId, regular, Instant.parse("2026-11-17T22:30:00Z"));
        batchGenerator.generateEmailDigest(LocalDate.of(2026, 11, 18));

        // Update conditions of subEdit so 307032 no longer matches BEFORE TX2 runs -> TX2 cancels NO_ITEMS with 0 HTTP!
        String editTok = crypto.capability(AlertsCrypto.Purpose.MANAGE, subEdit, 1);
        assertThat(subscriptions.update(editTok,
                new SubscriptionService.Conditions(null, "R3013", null, null, "없는키워드999", true, false), "198.51.100.1"))
                .isEqualTo(SubscriptionService.State.UPDATED);

        // While TX2 runs on subUnsub holding FOR SHARE during HTTP, concurrent unsubscribe waits and commits AFTER TX2
        List<DeliveryLedger.Claim> claims = ledger.claimDue(10);
        assertThat(claims).hasSize(2);
        DeliveryLedger.Claim cUnsub = claims.stream()
                .filter(c -> ledger.loadAsyncJob(c.id()).orElseThrow().subscriptionId().equals(subUnsub)).findFirst().orElseThrow();
        DeliveryLedger.Claim cEdit = claims.stream()
                .filter(c -> ledger.loadAsyncJob(c.id()).orElseThrow().subscriptionId().equals(subEdit)).findFirst().orElseThrow();

        // Dispatch cEdit -> cancels cleanly
        dispatcher.dispatchClaim(cEdit);
        assertThat(SENT_MAILS).isEmpty();
        assertThat(jdbc.sql("SELECT status FROM notification WHERE id = :id").param("id", cEdit.id()).query(String.class).single())
                .isEqualTo("CANCELLED");

        // Concurrent lock race on subUnsub
        CountDownLatch inHttp = new CountDownLatch(1);
        CountDownLatch releaseHttp = new CountDownLatch(1);
        ON_MAIL_CALL.set(() -> {
            inHttp.countDown();
            try { releaseHttp.await(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        });
        String unsubTok = crypto.capability(AlertsCrypto.Purpose.UNSUBSCRIBE, subUnsub, 1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<?> dispatchFuture = pool.submit(() -> dispatcher.dispatchClaim(cUnsub));
            assertThat(inHttp.await(5, TimeUnit.SECONDS)).isTrue();
            Future<SubscriptionService.State> unsubFuture = pool.submit(() ->
                    subscriptions.unsubscribe(unsubTok, AlertsCrypto.Purpose.UNSUBSCRIBE, "198.51.100.2"));
            Thread.sleep(100);
            assertThat(unsubFuture.isDone()).isFalse(); // serialized behind TX2's FOR SHARE
            releaseHttp.countDown();
            dispatchFuture.get(8, TimeUnit.SECONDS);
            assertThat(unsubFuture.get(8, TimeUnit.SECONDS)).isEqualTo(SubscriptionService.State.UNSUBSCRIBED);
        } finally {
            releaseHttp.countDown();
            ON_MAIL_CALL.set(() -> {});
        }
        assertThat(jdbc.sql("SELECT count(*) FROM subscription WHERE id = :id").param("id", subUnsub).query(Integer.class).single())
                .isZero();
    }

    // ---- 6. RFC 8058 round-trip + Prometheus M2 metrics & zero-PII verification ----

    @Test
    void rfc8058OneClickUnsubscribeAndPrometheusMetricsWorkWithoutPii() throws Exception {
        Instant tConfirm = Instant.parse("2026-11-17T22:00:00Z");
        UUID sub = insertActiveEmail("rfc8058-secret@example.com", null, null, null, null, true, false, tConfirm);
        UUID discDisabled = insertActiveDiscord("https://discord.com/api/webhooks/888/secret-hook-token",
                null, null, null, null, true, false, tConfirm);
        String regular = fixtureBody(307032L, r -> r.put("pbancEndYmd", "20261125").put("ongoingYn", "Y"));
        long regId = runs.claim("REGULAR", "2026-11-18T07:30:00+09:00").orElseThrow();
        ingestor.applyRegular(regId, regular, Instant.parse("2026-11-17T22:30:00Z"));
        batchGenerator.generateEmailDigest(LocalDate.of(2026, 11, 18));

        synchronized (WEBHOOK_SCRIPT) { WEBHOOK_SCRIPT.addLast(new SendOutcome.Result(SendOutcome.WEBHOOK_404, 404, null)); }
        worker.runOnce();
        assertThat(SENT_MAILS).hasSize(1);

        // Extract RFC 8058 List-Unsubscribe URL and POST without Origin header (mail provider behavior)
        JsonNode mailJson = JSON.readTree(SENT_MAILS.getFirst().body());
        String headerVal = mailJson.path("headers").path("List-Unsubscribe").asString();
        assertThat(headerVal).startsWith("<https://gongchae.example/subscriptions/unsubscribe/one-click?t=").endsWith(">");
        String pathAndQuery = headerVal.substring(1, headerVal.length() - 1).replace("https://gongchae.example", "");
        mvc.perform(post(pathAndQuery).param("List-Unsubscribe", "One-Click"))
                .andExpect(status().isOk())
                .andExpect(content().string("unsubscribed"));
        assertThat(Files.readString(JOURNAL, UTF_8)).contains(sub.toString());
        assertThat(jdbc.sql("SELECT count(*) FROM subscription WHERE id = :id").param("id", sub).query(Integer.class).single())
                .isZero();

        // Scrape management /actuator/prometheus and verify #15 M2 gauges + PRD metrics with zero PII/secrets
        try (var http = java.net.http.HttpClient.newHttpClient()) {
            var resp = http.send(java.net.http.HttpRequest.newBuilder(
                    java.net.URI.create("http://127.0.0.1:" + managementPort + "/actuator/prometheus")).build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            assertThat(resp.statusCode()).isEqualTo(200);
            String metricsBody = resp.body();
            assertThat(metricsBody)
                    .contains("alerts_notification_backlog")
                    .contains("alerts_notification_sending_stuck")
                    .contains("alerts_notification_failed_last1h")
                    .contains("alerts_notification_uncertain_last24h")
                    .contains("alerts_notification_sent_last24h")
                    .contains("alerts_notification_duplicate_risk_last24h")
                    .contains("alerts_subscriptions_active")
                    .contains("alerts_subscriptions_disabled")
                    .contains("alerts_send_total_used")
                    .contains("alerts_delivery_delay_max_seconds")
                    .contains("alerts_delivery_attempts_total")
                    .contains("alerts_delivery_latency_seconds")
                    .doesNotContain("rfc8058-secret@example.com")
                    .doesNotContain("secret-hook-token")
                    .doesNotContain(sub.toString())
                    .doesNotContain(discDisabled.toString());
        }
    }

    // ---- 7. Outbox & daily batch vs concurrent unsubscribe serialization (no FK 23503 rollback) ----

    @Test
    void outboxAndDigestSerializeWithConcurrentUnsubscribeWithoutRollback() throws Exception {
        Instant tConfirm = Instant.parse("2026-11-17T22:00:00Z");
        UUID discDeleting = insertActiveDiscord("https://discord.com/api/webhooks/701/del", null, null, null, null, true, false, tConfirm);
        UUID discSurvivor = insertActiveDiscord("https://discord.com/api/webhooks/702/surv", null, null, null, null, true, false, tConfirm);
        UUID emailDeleting = insertActiveEmail("del@example.com", null, null, null, null, true, false, tConfirm);
        UUID emailSurvivor = insertActiveEmail("surv@example.com", null, null, null, null, true, false, tConfirm);

        // (1) Mid-flight unsubscribe commit during PostingIngestor.applyRegular -> no FK 23503, posting + survivor preserved, 0 HTTP to deleted
        String reg1 = fixtureBody(307032L, r -> r.put("pbancEndYmd", "20261125").put("ongoingYn", "Y"));
        long run1 = runs.claim("REGULAR", "2026-11-18T10:00:00+09:00").orElseThrow();
        try (var holder = dataSource.getConnection(); var pool = Executors.newSingleThreadExecutor()) {
            holder.setAutoCommit(false);
            try {
                try (var ps = holder.prepareStatement("SELECT id FROM subscription WHERE id = ? FOR UPDATE")) {
                    ps.setObject(1, discDeleting);
                    ps.executeQuery().close();
                }
                try (var del = holder.prepareStatement("DELETE FROM subscription WHERE id = ?")) {
                    del.setObject(1, discDeleting);
                    del.executeUpdate();
                }
                Future<?> regularFuture = pool.submit(() ->
                        ingestor.applyRegular(run1, reg1, Instant.parse("2026-11-18T01:00:00Z")));
                awaitLockWait("SELECT 1 FROM subscription");
                holder.commit();
                regularFuture.get(8, TimeUnit.SECONDS);
            } finally {
                holder.rollback();
            }
        }
        assertThat(jdbc.sql("SELECT status FROM posting WHERE sn = 307032").query(String.class).single()).isEqualTo("OPEN");
        assertThat(jdbc.sql("SELECT count(*) FROM notification WHERE subscription_id = :s").param("s", discDeleting)
                .query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM notification WHERE subscription_id = :s AND kind = 'DISCORD_NEW'")
                .param("s", discSurvivor).query(Integer.class).single()).isEqualTo(1);

        // (2) Mid-flight unsubscribe commit during AlertBatchGenerator.generateEmailDigest -> no FK 23503, survivor digest preserved
        NOW.set(Instant.parse("2026-11-18T01:00:00Z"));
        try (var holder = dataSource.getConnection(); var pool = Executors.newSingleThreadExecutor()) {
            holder.setAutoCommit(false);
            try {
                try (var ps = holder.prepareStatement("SELECT id FROM subscription WHERE id = ? FOR UPDATE")) {
                    ps.setObject(1, emailDeleting);
                    ps.executeQuery().close();
                }
                try (var del = holder.prepareStatement("DELETE FROM subscription WHERE id = ?")) {
                    del.setObject(1, emailDeleting);
                    del.executeUpdate();
                }
                Future<Integer> digestFuture = pool.submit(() ->
                        batchGenerator.generateEmailDigest(LocalDate.of(2026, 11, 18)));
                awaitLockWait("SELECT 1 FROM subscription");
                holder.commit();
                assertThat(digestFuture.get(8, TimeUnit.SECONDS)).isEqualTo(1);
            } finally {
                holder.rollback();
            }
        }
        assertThat(jdbc.sql("SELECT count(*) FROM notification WHERE subscription_id = :s").param("s", emailDeleting)
                .query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM notification WHERE subscription_id = :s AND kind = 'EMAIL_DIGEST'")
                .param("s", emailSurvivor).query(Integer.class).single()).isEqualTo(1);

        // Dispatch survivor batches first, then verify creation-first (FOR KEY SHARE held first) serializes with unsubscribe
        worker.runOnce();
        assertThat(SENT_WEBHOOKS).hasSize(1);
        assertThat(SENT_WEBHOOKS.getFirst().url().normalized()).contains("702/surv");
        assertThat(SENT_MAILS).hasSize(1);
        assertThat(SENT_MAILS.getFirst().body()).contains("surv@example.com").doesNotContain("del@example.com");

        // (3) Creation-first (FOR KEY SHARE held on first Discord sub while paused on second) -> concurrent unsubscribe waits then cascades cleanly
        UUID discExtra = insertActiveDiscord("https://discord.com/api/webhooks/703/extra", null, null, null, null, true, false, tConfirm);
        List<UUID> ordered = jdbc.sql("""
                SELECT id FROM subscription WHERE channel = 'DISCORD' AND status = 'ACTIVE' ORDER BY id
                """).query(UUID.class).list();
        assertThat(ordered).hasSize(2);
        UUID firstSub = ordered.get(0);
        UUID secondSub = ordered.get(1);
        String firstUnsubTok = crypto.capability(AlertsCrypto.Purpose.UNSUBSCRIBE, firstSub, 1);

        String reg2 = fixtureBody(307031L, r -> r.put("pbancEndYmd", "20261126").put("ongoingYn", "Y"));
        long run2 = runs.claim("REGULAR", "2026-11-18T11:00:00+09:00").orElseThrow();
        SENT_WEBHOOKS.clear();

        try (var secondLock = dataSource.getConnection(); var pool = Executors.newFixedThreadPool(2)) {
            secondLock.setAutoCommit(false);
            try {
                try (var ps = secondLock.prepareStatement("SELECT id FROM subscription WHERE id = ? FOR UPDATE")) {
                    ps.setObject(1, secondSub);
                    ps.executeQuery().close();
                }
                Future<?> regularFuture = pool.submit(() ->
                        ingestor.applyRegular(run2, reg2, Instant.parse("2026-11-18T02:00:00Z")));
                awaitLockWait("SELECT 1 FROM subscription");

                // regularFuture now holds FOR KEY SHARE on firstSub while blocked on secondSub -> unsubscribe(firstSub) must wait
                Future<SubscriptionService.State> unsubFuture = pool.submit(() ->
                        subscriptions.unsubscribe(firstUnsubTok, AlertsCrypto.Purpose.UNSUBSCRIBE, "198.51.100.9"));
                Thread.sleep(100);
                assertThat(unsubFuture.isDone()).isFalse();

                // Release lock on secondSub -> applyRegular commits, then unsubscribe acquires FOR UPDATE & cascades firstSub!
                secondLock.rollback();
                regularFuture.get(8, TimeUnit.SECONDS);
                assertThat(unsubFuture.get(8, TimeUnit.SECONDS)).isEqualTo(SubscriptionService.State.UNSUBSCRIBED);
            } finally {
                secondLock.rollback();
            }
        }

        // Dispatch after serialization: secondSub receives 1 webhook, unsubscribed firstSub receives 0 HTTP!
        NOW.set(Instant.parse("2026-11-18T02:00:00Z"));
        worker.runOnce();
        assertThat(SENT_WEBHOOKS).hasSize(1);
        assertThat(jdbc.sql("SELECT count(*) FROM subscription WHERE id = :s").param("s", firstSub).query(Integer.class).single())
                .isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM notification WHERE subscription_id = :s").param("s", firstSub)
                .query(Integer.class).single()).isZero();
        assertThat(discExtra).isNotNull();
    }

    private void awaitLockWait(String sqlSubstring) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbc.sql("""
                    SELECT count(*) FROM pg_stat_activity
                    WHERE datname = current_database() AND wait_event_type = 'Lock' AND query LIKE :q
                    """).param("q", "%" + sqlSubstring + "%").query(Integer.class).single();
            if (waiting != null && waiting > 0) return;
            Thread.sleep(20);
        }
        throw new AssertionError("Timed out waiting for PostgreSQL lock wait on: " + sqlSubstring);
    }
}

