package kr.gongchae.bell.alerts;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import kr.gongchae.bell.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** #6 flows with alerts enabled: local provider fakes, an isolated Testcontainers DB, collection off (IntegrationTestSupport). */
@Import(SubscriptionFlowIntegrationTest.Fakes.class)
@TestPropertySource(properties = {
        "app.base-url=https://gongchae.example",
        "alerts.enabled=true",
        "alerts.webhook-key-id=k1",
        "alerts.mail.api-key=re_fixture_not_a_key",
        "alerts.mail.from=공채벨 <alerts@gongchae.example>",
        "alerts.privacy.officer=개인정보 담당(시험값)",
        "alerts.privacy.contact=시험 연락처",
        "alerts.privacy.policy-version=test-1",
        "alerts.privacy.effective-date=2026-11-16",
        "alerts.privacy.hosting-location=시험 위치",
        "alerts.privacy.overseas-basis=시험 근거",
        "alerts.privacy.resend-retention=시험 보관"
})
class SubscriptionFlowIntegrationTest extends IntegrationTestSupport {
    static final Path JOURNAL;
    static {
        // Prepared before the context starts, as the operator does before enabling alerts.
        try { JOURNAL = Files.createFile(Files.createTempDirectory("erasure").resolve("erasure.log")); }
        catch (IOException e) { throw new UncheckedIOException(e); }
    }
    static final String ORIGIN = "https://gongchae.example";
    static final String WEBHOOK = "https://discord.com/api/webhooks/123456789/fixture-token";

    @DynamicPropertySource
    static void journalAndTestKeys(DynamicPropertyRegistry registry) {
        registry.add("alerts.erasure-log", JOURNAL::toString);
        // Test-only keys built from fixed ASCII at runtime (no key-like literal in the source).
        registry.add("alerts.token-key", () -> AlertsSecurityTest.KEY_A);
        registry.add("alerts.hmac-key", () -> AlertsSecurityTest.KEY_B);
        registry.add("alerts.webhook-key", () -> AlertsSecurityTest.KEY_C);
    }

    static final List<String> MAILS = new CopyOnWriteArrayList<>();
    static final AtomicReference<SendOutcome> MAIL_RESULT = new AtomicReference<>(SendOutcome.SUCCESS);
    static final AtomicInteger WEBHOOK_CALLS = new AtomicInteger();
    static final AtomicReference<SendOutcome> WEBHOOK_RESULT = new AtomicReference<>(SendOutcome.SUCCESS);
    /** Fake monotonic clock: real time plus AHEAD. SKIP_AFTER_START moves it ahead right after the next reading. */
    static final AtomicLong AHEAD = new AtomicLong();
    static final AtomicLong SKIP_AFTER_START = new AtomicLong();

    @TestConfiguration
    static class Fakes {
        @Bean @Primary MailTransport fakeMail() {
            return (payload, key, timeout) -> { MAILS.add(new String(payload, UTF_8)); return SendOutcome.Result.of(MAIL_RESULT.get()); };
        }
        @Bean @Primary WebhookTransport fakeWebhook() {
            return (url, payload, timeout) -> { WEBHOOK_CALLS.incrementAndGet(); return SendOutcome.Result.of(WEBHOOK_RESULT.get()); };
        }
        @Bean @Primary SyncSender.Timer noWaitTimer() {
            return new SyncSender.Timer() {
                @Override public long nanoTime() {
                    long now = System.nanoTime() + AHEAD.get();
                    AHEAD.addAndGet(SKIP_AFTER_START.getAndSet(0));  // the request's start() reads before the jump
                    return now;
                }
                @Override public void sleep(Duration duration) { }
            };
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired AlertsCrypto crypto;
    @Autowired DataSource dataSource;

    @BeforeEach
    void clean() throws IOException {
        jdbc.sql("TRUNCATE subscription, notification, notification_attempt, notification_item, rate_limit CASCADE").update();
        jdbc.sql("""
                INSERT INTO code_name(type, code, name) VALUES ('NCS','R600020','정보통신'), ('REGION','R3010','서울'), ('HIRE','R1010','정규직')
                ON CONFLICT DO NOTHING
                """).update();
        MAILS.clear();
        MAIL_RESULT.set(SendOutcome.SUCCESS);
        WEBHOOK_CALLS.set(0);
        WEBHOOK_RESULT.set(SendOutcome.SUCCESS);
        SKIP_AFTER_START.set(0);
        Files.write(JOURNAL, new byte[0]);
    }

    static RequestPostProcessor from(String ip) { return request -> { request.setRemoteAddr(ip); return request; }; }

    MockHttpServletRequestBuilder signup(String email) {
        return post("/subscriptions/email").header("Origin", ORIGIN).param("email", email).param("ncs", "R600020")
                .param("notifyNew", "true").param("consent", "true").param("age14", "true");
    }

    static String confirmToken(String mail) {
        var matcher = Pattern.compile("confirm\\?t=([A-Za-z0-9_-]{43})").matcher(mail);
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    String stateOf(String email) {
        return jdbc.sql("SELECT status FROM subscription WHERE email = :e").param("e", email).query(String.class).single();
    }

    UUID id(String email) {
        return jdbc.sql("SELECT id FROM subscription WHERE email = :e").param("e", email).query(UUID.class).single();
    }

    String confirmed(String email) throws Exception {
        mvc.perform(signup(email)).andExpect(status().isOk());
        mvc.perform(post("/subscriptions/confirm").param("t", confirmToken(MAILS.getLast()))).andExpect(status().isOk());
        return crypto.capability(AlertsCrypto.Purpose.MANAGE, id(email), 1);
    }

    int tokenFailures() {
        return jdbc.sql("SELECT coalesce(sum(count), 0) FROM rate_limit WHERE scope = 'TOKEN_FAIL'").query(Integer.class).single();
    }

    @Test
    void onlyAValidConfirmPostActivatesAndConfirmTokensAreSingleUse() throws Exception {
        mvc.perform(signup("Reader@Example.com")).andExpect(status().isOk()).andExpect(content().string(containsString("신청을 받았습니다")));
        assertThat(MAILS).hasSize(1);
        assertThat(MAILS.getFirst()).contains("reader@example.com").contains("https://gongchae.example/subscriptions/confirm?t=");
        assertThat(stateOf("reader@example.com")).as("a sent mail does not activate").isEqualTo("PENDING");
        assertThat(jdbc.sql("SELECT status || '|' || attempts FROM notification WHERE kind = 'EMAIL_CONFIRM'").query(String.class).single()).isEqualTo("SENT|1");
        assertThat(jdbc.sql("SELECT consent_version || '|' || age_confirmed FROM subscription").query(String.class).single()).isEqualTo("test-1|true");
        String token = confirmToken(MAILS.getFirst());

        mvc.perform(get("/subscriptions/confirm").param("t", token)).andExpect(status().isOk())
                .andExpect(header().string("Referrer-Policy", "no-referrer")).andExpect(header().string("Cache-Control", "no-store"));
        assertThat(stateOf("reader@example.com")).as("GET changes nothing").isEqualTo("PENDING");
        mvc.perform(post("/subscriptions/confirm").param("t", "x".repeat(43))).andExpect(status().isBadRequest());
        mvc.perform(post("/subscriptions/confirm").header("Origin", "null").param("t", token)).andExpect(status().isOk())
                .andExpect(content().string(containsString("/subscriptions/manage?t=")));
        assertThat(jdbc.sql("SELECT status || '|' || (confirmed_at IS NOT NULL) || '|' || (confirm_token_hash IS NULL) FROM subscription")
                .query(String.class).single()).isEqualTo("ACTIVE|true|true");
        mvc.perform(post("/subscriptions/confirm").param("t", token)).andExpect(status().isBadRequest());

        mvc.perform(signup("late@example.com")).andExpect(status().isOk());
        jdbc.sql("UPDATE subscription SET confirm_expires_at = now() - interval '1 second' WHERE email = 'late@example.com'").update();
        mvc.perform(post("/subscriptions/confirm").param("t", confirmToken(MAILS.getLast()))).andExpect(status().isBadRequest());
        assertThat(stateOf("late@example.com")).isEqualTo("PENDING");
        assertThat(tokenFailures()).isEqualTo(3);
    }

    @Test
    void anActiveSubscriptionIsNeverOverwrittenAndConcurrentSignupsLeaveOneRow() throws Exception {
        confirmed("kept@example.com");
        int mails = MAILS.size();
        mvc.perform(post("/subscriptions/email").header("Origin", ORIGIN).param("email", "kept@example.com").param("region", "R3010")
                .param("notifyDeadline", "true").param("consent", "true").param("age14", "true"))
                .andExpect(status().isOk()).andExpect(content().string(containsString("신청을 받았습니다")));
        assertThat(MAILS).as("no mail for an active address").hasSize(mails);
        assertThat(jdbc.sql("SELECT conditions->>'ncs' || '|' || notify_new || '|' || notify_deadline FROM subscription").query(String.class).single())
                .isEqualTo("R600020|true|false");

        var ready = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> { ready.await(); return mvc.perform(signup("race@example.com").with(from("198.51.100.1"))).andReturn().getResponse().getStatus(); });
            var b = pool.submit(() -> { ready.await(); return mvc.perform(signup("race@example.com").with(from("198.51.100.2"))).andReturn().getResponse().getStatus(); });
            ready.countDown();
            assertThat(List.of(a.get(), b.get())).containsOnly(200);
        }
        assertThat(jdbc.sql("SELECT count(*) FROM subscription WHERE email = 'race@example.com'").query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void originRulesAndTokenFailureGateKeepTheirOrder() throws Exception {
        mvc.perform(post("/subscriptions/email").param("email", "a@example.com")).andExpect(status().isForbidden());
        mvc.perform(post("/subscriptions/email").header("Origin", "https://evil.example").param("email", "a@example.com")).andExpect(status().isForbidden());
        mvc.perform(post("/subscriptions/email").header("Origin", "null").param("email", "a@example.com")).andExpect(status().isForbidden());
        mvc.perform(post("/subscriptions/email").header("Referer", ORIGIN + "/subscriptions/new").param("email", "ref@example.com")
                .param("notifyNew", "true").param("consent", "true").param("age14", "true")).andExpect(status().isOk());
        assertThat(MAILS).hasSize(1);

        mvc.perform(post("/subscriptions/confirm").header("Origin", "https://evil.example").param("t", "x")).andExpect(status().isForbidden());
        assertThat(tokenFailures()).as("a foreign Origin is refused before any token check and is not counted").isZero();
        mvc.perform(post("/subscriptions/confirm").header("Origin", "null").param("t", "x")).andExpect(status().isBadRequest());
        mvc.perform(post("/subscriptions/confirm").param("t", "x")).andExpect(status().isBadRequest());
        assertThat(tokenFailures()).isEqualTo(2);

        String manage = confirmed("gate@example.com");
        for (int i = 0; i < 30; i++) mvc.perform(get("/subscriptions/manage").param("t", "bad-" + i).with(from("203.0.113.7"))).andExpect(status().isBadRequest());
        mvc.perform(get("/subscriptions/manage").param("t", manage).with(from("203.0.113.7"))).andExpect(status().isTooManyRequests());
        mvc.perform(get("/subscriptions/manage").param("t", manage).with(from("203.0.113.8"))).andExpect(status().isOk());
    }

    @Test
    void managementIsStrictAndCapabilitiesAreBoundToPurposeAndSubscription() throws Exception {
        String manage = confirmed("owner@example.com");
        String other = confirmed("other@example.com");
        mvc.perform(get("/subscriptions/manage").param("t", manage)).andExpect(status().isOk())
                .andExpect(content().string(containsString("o***@example.com"))).andExpect(content().string(not(containsString("owner@example.com"))))
                .andExpect(header().string("Referrer-Policy", "no-referrer")).andExpect(header().string("Cache-Control", "no-store"));
        String forged = id("owner@example.com") + other.substring(36);
        mvc.perform(get("/subscriptions/manage").param("t", forged)).andExpect(status().isBadRequest());
        // a manage token is not an unsubscribe token
        mvc.perform(post("/subscriptions/unsubscribe").param("t", manage)).andExpect(status().isBadRequest());

        String before = jdbc.sql("SELECT updated_at::text FROM subscription WHERE email = 'owner@example.com'").query(String.class).single();
        mvc.perform(post("/subscriptions/manage").param("t", manage).param("ncs", "R999999").param("notifyNew", "true")).andExpect(status().isBadRequest());
        mvc.perform(post("/subscriptions/manage").param("t", manage).param("ncs", "R600020")).andExpect(status().isBadRequest());
        mvc.perform(post("/subscriptions/manage").param("t", manage).param("q", "가".repeat(101)).param("notifyNew", "true")).andExpect(status().isBadRequest());
        assertThat(jdbc.sql("SELECT updated_at::text FROM subscription WHERE email = 'owner@example.com'").query(String.class).single()).isEqualTo(before);
        mvc.perform(post("/subscriptions/manage").param("t", manage).param("region", "R3010").param("q", " 전산 ").param("notifyDeadline", "true"))
                .andExpect(status().isOk()).andExpect(content().string(containsString("조건을 바꿨습니다")));
        assertThat(jdbc.sql("""
                SELECT coalesce(conditions->>'ncs','-') || '|' || (conditions->>'region') || '|' || (conditions->>'q') || '|' || notify_new || '|' || notify_deadline
                FROM subscription WHERE email = 'owner@example.com'
                """).query(String.class).single()).isEqualTo("-|R3010|전산|false|true");
    }

    @Test
    void unsubscribeWritesTheJournalThenDeletesWithEveryChildAndOneClickWorks() throws Exception {
        confirmed("leave@example.com");
        UUID id = id("leave@example.com");
        assertThat(jdbc.sql("SELECT count(*) FROM notification_attempt").query(Integer.class).single()).isEqualTo(1);
        String unsubscribe = crypto.capability(AlertsCrypto.Purpose.UNSUBSCRIBE, id, 1);
        mvc.perform(get("/subscriptions/unsubscribe").param("t", unsubscribe)).andExpect(status().isOk());
        assertThat(stateOf("leave@example.com")).as("GET changes nothing").isEqualTo("ACTIVE");
        mvc.perform(post("/subscriptions/unsubscribe").param("t", unsubscribe)).andExpect(status().isOk())
                .andExpect(content().string(containsString("해지했습니다")));
        assertThat(jdbc.sql("SELECT count(*) FROM subscription").query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM notification").query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM notification_attempt").query(Integer.class).single()).isZero();
        assertThat(new ErasureJournal(JOURNAL).readStrict()).containsExactly(id);
        mvc.perform(post("/subscriptions/unsubscribe").param("t", unsubscribe)).andExpect(status().isBadRequest());

        confirmed("click@example.com");
        UUID click = id("click@example.com");
        String token = crypto.capability(AlertsCrypto.Purpose.UNSUBSCRIBE, click, 1);
        mvc.perform(post("/subscriptions/unsubscribe/one-click").param("t", token)).andExpect(status().isBadRequest());
        mvc.perform(post("/subscriptions/unsubscribe/one-click").param("t", token).param("List-Unsubscribe", "One-Click"))
                .andExpect(status().isOk()).andExpect(content().string("unsubscribed"));
        assertThat(new ErasureJournal(JOURNAL).readStrict()).containsExactly(id, click);
    }

    @Test
    void recoveryAnswersTheSameWhetherOrNotTheAddressIsSubscribed() throws Exception {
        confirmed("known@example.com");
        int mails = MAILS.size();
        String known = mvc.perform(post("/subscriptions/recover/email").header("Origin", ORIGIN).param("email", "known@example.com"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(UTF_8);
        String unknown = mvc.perform(post("/subscriptions/recover/email").header("Origin", ORIGIN).param("email", "nobody@example.com"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(UTF_8);
        assertThat(known).isEqualTo(unknown);
        assertThat(MAILS).hasSize(mails + 1);
        assertThat(MAILS.getLast()).contains("/subscriptions/manage?t=").contains("/subscriptions/unsubscribe?t=");
        assertThat(jdbc.sql("SELECT status FROM notification WHERE kind = 'EMAIL_RECOVER'").query(String.class).single()).isEqualTo("SENT");

        MAIL_RESULT.set(SendOutcome.QUOTA_DAILY);  // a provider failure does not change the answer
        String failed = mvc.perform(post("/subscriptions/recover/email").header("Origin", ORIGIN).param("email", "known@example.com"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(UTF_8);
        assertThat(failed).isEqualTo(known);
        for (int i = 0; i < 2; i++) mvc.perform(post("/subscriptions/recover/email").header("Origin", ORIGIN).param("email", "nobody@example.com"));
        mvc.perform(post("/subscriptions/recover/email").header("Origin", ORIGIN).param("email", "nobody@example.com")).andExpect(status().isTooManyRequests());
    }

    @Test
    void discordRegistersOnlyAfterATestStoresTheSealedUrlAndNeverTestsALiveDestinationAgain() throws Exception {
        mvc.perform(post("/subscriptions/discord").header("Origin", ORIGIN).param("webhook", "https://discord.com.evil.example/api/webhooks/1/t")
                .param("notifyNew", "true").param("consent", "true").param("age14", "true")).andExpect(status().isBadRequest());
        WEBHOOK_RESULT.set(SendOutcome.WEBHOOK_404);
        mvc.perform(discord()).andExpect(status().isBadRequest()).andExpect(content().string(containsString("찾을 수 없습니다")));
        assertThat(jdbc.sql("SELECT count(*) FROM subscription").query(Integer.class).single()).as("a failed test stores nothing").isZero();

        WEBHOOK_RESULT.set(SendOutcome.SUCCESS);
        WEBHOOK_CALLS.set(0);
        mvc.perform(discord()).andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().string(containsString("/subscriptions/manage?t=")));
        assertThat(WEBHOOK_CALLS).hasValue(1);
        var row = jdbc.sql("SELECT id, webhook_enc, webhook_key_id, status, confirmed_at IS NOT NULL FROM subscription")
                .query((rs, n) -> List.of(rs.getObject(1), rs.getBytes(2), rs.getString(3), rs.getString(4), rs.getBoolean(5))).single();
        UUID id = (UUID) row.get(0);
        assertThat(new String((byte[]) row.get(1), UTF_8)).doesNotContain("fixture-token");
        assertThat(crypto.decryptWebhook(id, (byte[]) row.get(1), (String) row.get(2))).isEqualTo(WEBHOOK);
        assertThat(row.subList(3, 5)).containsExactly("ACTIVE", true);
        assertThat(jdbc.sql("""
                SELECT n.status || '|' || n.attempts || '|' || a.outcome FROM notification n JOIN notification_attempt a ON a.notification_id = n.id
                WHERE n.kind = 'DISCORD_TEST'
                """).query(String.class).single()).isEqualTo("SENT|1|SUCCESS");

        mvc.perform(discord()).andExpect(status().isOk()).andExpect(content().string(containsString("이미 등록된 웹훅")));
        assertThat(WEBHOOK_CALLS).as("no test message to an already live webhook").hasValue(1);
        mvc.perform(post("/subscriptions/recover/discord").header("Origin", ORIGIN).param("webhook", "https://discordapp.com/api/webhooks/123456789/fixture-token"))
                .andExpect(status().isOk()).andExpect(content().string(containsString("/subscriptions/manage?t=" + id)));
    }

    MockHttpServletRequestBuilder discord() {
        return post("/subscriptions/discord").header("Origin", ORIGIN).param("webhook", WEBHOOK).param("notifyDeadline", "true")
                .param("consent", "true").param("age14", "true");
    }

    @Test
    void signupPagesShowNoticeConsentAndListCtaAndRefuseMissingConsentOrKinds() throws Exception {
        mvc.perform(get("/subscriptions/new").param("ncs", "R600020").param("region", "NOPE")).andExpect(status().isOk())
                .andExpect(content().string(containsString("개인정보 수집·이용 안내")))
                .andExpect(content().string(containsString("만 14세 이상입니다")))
                .andExpect(content().string(containsString("<option value=\"R600020\" selected=\"selected\">정보통신</option>")))
                .andExpect(content().string(containsString("name=\"consent\" value=\"true\" id=\"email-consent\" required>")));
        mvc.perform(get("/privacy")).andExpect(status().isOk()).andExpect(content().string(containsString("개인정보 담당(시험값)")))
                .andExpect(content().string(containsString("Plus Five Five, Inc.")));
        mvc.perform(get("/jobs").param("ncs", "R600020")).andExpect(status().isOk())
                // unset filters stay as empty arguments (agy's PR #13 note said they are left out; they are not)
                .andExpect(content().string(containsString("/subscriptions/new?ncs=R600020&amp;region=&amp;hire=&amp;career=&amp;q=\"")));
        mvc.perform(get("/subscriptions/new?ncs=R600020&region=&hire=&career=&q=")).andExpect(status().isOk())
                .andExpect(content().string(containsString("<option value=\"R600020\" selected=\"selected\">정보통신</option>")));
        mvc.perform(post("/subscriptions/email").header("Origin", ORIGIN).param("email", "x@example.com").param("notifyNew", "true")
                .param("age14", "true")).andExpect(status().isBadRequest()).andExpect(content().string(containsString("동의해야")));
        mvc.perform(post("/subscriptions/email").header("Origin", ORIGIN).param("email", "x@example.com").param("notifyNew", "true")
                .param("consent", "true")).andExpect(status().isBadRequest()).andExpect(content().string(containsString("만 14세 이상만")));
        mvc.perform(post("/subscriptions/email").header("Origin", ORIGIN).param("email", "x@example.com").param("consent", "true").param("age14", "true"))
                .andExpect(status().isBadRequest()).andExpect(content().string(containsString("하나 이상")));
        mvc.perform(signup("not-an-email")).andExpect(status().isBadRequest());
        assertThat(jdbc.sql("SELECT count(*) FROM subscription").query(Integer.class).single()).isZero();
        for (int i = 0; i < 3; i++) mvc.perform(signup("same@example.com")).andExpect(status().isOk());
        mvc.perform(signup("same@example.com")).andExpect(status().isTooManyRequests());
    }

    MockHttpServletRequestBuilder recover(String email) {
        return post("/subscriptions/recover/email").header("Origin", ORIGIN).param("email", email);
    }

    void awaitLockWait() throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            if (jdbc.sql("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND datname = current_database()")
                    .query(Integer.class).single() > 0) return;
            Thread.sleep(50);
        }
        throw new AssertionError("no transaction waited on the lock");
    }

    /** Root C1 / agy R-1: before the parent lock, the job insert after the unsubscribe's delete failed the FK (500). */
    @Test
    void aRecoveryRacingAnUnsubscribeAnswersTheSameAndSendsNothing() throws Exception {
        confirmed("racer@example.com");
        UUID id = id("racer@example.com");
        int mails = MAILS.size();
        String fixed = mvc.perform(recover("nobody@example.com")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(UTF_8);
        try (Connection unsubscribe = dataSource.getConnection(); var pool = Executors.newSingleThreadExecutor()) {
            unsubscribe.setAutoCommit(false);
            // The unsubscribe's own DB statements (its journal line is outside the DB): the row lock, then the delete.
            try (var lock = unsubscribe.prepareStatement("SELECT token_version FROM subscription WHERE id = ? FOR UPDATE")) {
                lock.setObject(1, id);
                lock.executeQuery();
            }
            try (var delete = unsubscribe.prepareStatement("DELETE FROM subscription WHERE id = ?")) {
                delete.setObject(1, id);
                delete.executeUpdate();
            }
            Future<MvcResult> recovery = pool.submit(() -> mvc.perform(recover("racer@example.com")).andReturn());
            awaitLockWait();  // the recovery's read now waits for the unsubscribe
            unsubscribe.commit();
            MvcResult result = recovery.get(20, TimeUnit.SECONDS);
            assertThat(result.getResponse().getStatus()).isEqualTo(200);
            assertThat(result.getResponse().getContentAsString(UTF_8)).as("the fixed answer").isEqualTo(fixed);
        }
        assertThat(MAILS).as("nothing sent to an erased address").hasSize(mails);
        assertThat(jdbc.sql("SELECT count(*) FROM notification WHERE kind = 'EMAIL_RECOVER'").query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM subscription WHERE id = :id").param("id", id).query(Integer.class).single()).isZero();
    }

    /** Root C4: a lock wait before the send is cut to the request budget and ends as a 503, keeping nothing of the step. */
    @Test
    void aBoundedWaitThatRunsOutAnswersBusyInsideTheBudgetAndKeepsNothingOfTheSignup() throws Exception {
        try (Connection other = dataSource.getConnection()) {  // another sign-up of the same address, not yet committed
            other.setAutoCommit(false);
            try (var insert = other.prepareStatement("""
                    INSERT INTO subscription(id, channel, status, email, dest_hmac, conditions, notify_new, notify_deadline,
                      confirm_token_hash, confirm_expires_at, consent_version, consented_at, age_confirmed, created_at, updated_at)
                    VALUES (?, 'EMAIL', 'PENDING', 'busy@example.com', ?, '{}'::jsonb, true, false, ?, now() + interval '1 day',
                      'test-1', now(), true, now(), now())
                    """)) {
                insert.setObject(1, UUID.randomUUID());
                insert.setBytes(2, crypto.destination("EMAIL", "busy@example.com"));
                insert.setBytes(3, AlertsCrypto.sha256("other request"));
                insert.executeUpdate();
            }
            SKIP_AFTER_START.set(Duration.ofSeconds(20).toNanos());  // 20 s gone right after the start: 3 s left for waits
            long t0 = System.nanoTime();
            mvc.perform(signup("busy@example.com")).andExpect(status().isServiceUnavailable())
                    .andExpect(content().string(containsString("잠시 뒤 다시 시도해 주세요")));
            assertThat(Duration.ofNanos(System.nanoTime() - t0)).isBetween(Duration.ofMillis(2_500), Duration.ofMillis(8_000));
            other.rollback();
        }
        assertThat(MAILS).isEmpty();
        assertThat(jdbc.sql("SELECT count(*) FROM subscription").query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM notification").query(Integer.class).single()).isZero();
    }

    @Test
    void anUnsubscribeWithoutItsJournalFileDeletesNothing() throws Exception {
        confirmed("stay@example.com");
        String token = crypto.capability(AlertsCrypto.Purpose.UNSUBSCRIBE, id("stay@example.com"), 1);
        Files.delete(JOURNAL);  // the mount went away: the app does not create a fresh journal
        assertThatThrownBy(() -> mvc.perform(post("/subscriptions/unsubscribe").param("t", token)))
                .hasRootCauseInstanceOf(NoSuchFileException.class);
        assertThat(stateOf("stay@example.com")).isEqualTo("ACTIVE");
        assertThat(JOURNAL).doesNotExist();
    }
}
