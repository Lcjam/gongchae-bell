package kr.gongchae.bell.alerts;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import kr.gongchae.bell.catalog.CatalogRepository;
import kr.gongchae.bell.catalog.JobSearchCriteria;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Subscription lifecycle (#6, FR-A1~A3, A7). Every capability check runs the TOKEN_FAIL gate first (ADR-0014 order);
 * responses that could reveal whether an address is subscribed are the same for every case.
 */
@Service
public class SubscriptionService {
    private static final Logger log = LoggerFactory.getLogger(SubscriptionService.class);
    static final Duration CONFIRM_TTL = Duration.ofHours(24);
    private static final Pattern EMAIL = Pattern.compile("[^@\\s<>()\\[\\],;:\"]{1,64}@[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    public enum State { ACCEPTED, RATE_LIMITED, BLOCKED, INVALID, CONFIRMED, UPDATED, NOT_ACTIVE, UNSUBSCRIBED,
        FOUND, NONE, REGISTERED, ALREADY, WEBHOOK_NOT_FOUND, TEST_FAILED }

    public record Result(State state, String manageToken) {
        static Result of(State state) { return new Result(state, null); }
    }

    /** Stored subscription conditions: hiring filters plus at least one alert kind (FR-A1). No status/sort/page. */
    public record Conditions(String ncs, String region, String hire, String career, String q, boolean notifyNew, boolean notifyDeadline) {}

    public record ManageView(UUID id, String channel, String status, String target, Conditions conditions, String token) {}

    /** {@code view} is set only when {@code state} is FOUND. */
    public record ManageAccess(State state, ManageView view) {}

    /** Bad form input; {@code field} names the input, never its value. */
    public static final class InvalidInput extends IllegalArgumentException {
        private final String field;
        public InvalidInput(String field) { super("INVALID_INPUT"); this.field = field; }
        public String field() { return field; }
    }

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final AlertsCrypto crypto;
    private final RateLimiter limits;
    private final DeliveryLedger ledger;
    private final SyncSender sender;
    private final ErasureJournal journal;
    private final CatalogRepository catalog;
    private final AlertsProperties properties;
    private final Clock clock;
    private final String baseUrl;

    public SubscriptionService(JdbcClient jdbc, PlatformTransactionManager manager, AlertsCrypto crypto, RateLimiter limits,
            DeliveryLedger ledger, SyncSender sender, ErasureJournal journal, CatalogRepository catalog,
            AlertsProperties properties, Clock clock, @Value("${app.base-url:http://localhost:8080}") String baseUrl) {
        this.jdbc = jdbc; this.tx = new TransactionTemplate(manager); this.crypto = crypto; this.limits = limits;
        this.ledger = ledger; this.sender = sender; this.journal = journal; this.catalog = catalog;
        this.properties = properties; this.clock = clock;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    // ---- input ----

    /** Strict subscription conditions (shared allow-lists with the list page, which ignores bad values instead). */
    public Conditions conditions(String ncs, String region, String hire, String career, String q, boolean notifyNew, boolean notifyDeadline) {
        JobSearchCriteria strict;
        try { strict = JobSearchCriteria.strict(ncs, region, hire, career, q, catalog.allowedCodeSet(catalog.findFilterOptions())); }
        catch (JobSearchCriteria.InvalidCondition invalid) { throw new InvalidInput(invalid.field()); }
        if (!notifyNew && !notifyDeadline) throw new InvalidInput("kinds");
        return new Conditions(strict.ncs(), strict.region(), strict.hire(), strict.career(), strict.q(), notifyNew, notifyDeadline);
    }

    static String email(String raw) {
        String email = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (email.length() > 254 || !EMAIL.matcher(email).matches()) throw new InvalidInput("email");
        return email;
    }

    private static void consent(boolean agreed, boolean age14) {
        if (!agreed) throw new InvalidInput("consent");
        if (!age14) throw new InvalidInput("age14");
    }

    // ---- email sign-up and confirmation ----

    /**
     * FR-A1. Always ACCEPTED (or RATE_LIMITED): the answer never says whether the address is already subscribed.
     * Every DB wait before the send is bounded by the request budget (14-4). A wait that runs out throws
     * {@code CannotAcquireLockException}/{@code QueryTimeoutException}, and no part of the sign-up is committed.
     */
    public State subscribeEmail(String rawEmail, Conditions conditions, boolean agreed, boolean age14, String ip) {
        long start = sender.start();
        consent(agreed, age14);
        String email = email(rawEmail);
        Supplier<Duration> waits = () -> sender.waitLimit(start);
        if (!limits.acquire(RateLimiter.Scope.SUBSCRIBE, crypto.limitKey("SUBSCRIBE:ip", ip), RateLimiter.PER_IP, waits)
                || !limits.acquire(RateLimiter.Scope.SUBSCRIBE, crypto.limitKey("SUBSCRIBE:email", email), RateLimiter.PER_TARGET, waits))
            return State.RATE_LIMITED;
        String token = crypto.newConfirmToken();
        byte[] hash = AlertsCrypto.sha256(token);
        byte[] destination = crypto.destination("EMAIL", email);
        Signup signup = tx.execute(status -> signupEmail(email, destination, conditions, hash, waits));
        if (signup != null) deliver(signup.claim(), confirmJob(signup.id(), hash, email, token), start);
        return State.ACCEPTED;
    }

    private record Signup(UUID id, DeliveryLedger.Claim claim) {}

    /** Each statement that can wait gets what is left of the budget: the insert behind a concurrent one, and the row lock. */
    private Signup signupEmail(String email, byte[] destination, Conditions conditions, byte[] hash, Supplier<Duration> waits) {
        OffsetDateTime now = now();
        for (int attempt = 0; attempt < 2; attempt++) {
            limits.boundWaits(waits.get());
            Optional<UUID> inserted = jdbc.sql("""
                    INSERT INTO subscription(id, channel, status, email, dest_hmac, conditions, notify_new, notify_deadline,
                      confirm_token_hash, confirm_expires_at, consent_version, consented_at, age_confirmed, created_at, updated_at)
                    VALUES (:id, 'EMAIL', 'PENDING', :email, :dest, CAST(:conditions AS jsonb), :new, :deadline,
                      :hash, :expires, :consent, :now, true, :now, :now)
                    ON CONFLICT (channel, dest_hmac) WHERE status IN ('PENDING','ACTIVE') DO NOTHING RETURNING id
                    """).param("id", UUID.randomUUID()).param("email", email).param("dest", destination)
                    .param("conditions", json(conditions)).param("new", conditions.notifyNew()).param("deadline", conditions.notifyDeadline())
                    .param("hash", hash).param("expires", now.plus(CONFIRM_TTL)).param("consent", properties.privacy().policyVersion())
                    .param("now", now).query(UUID.class).optional();
            if (inserted.isPresent()) return new Signup(inserted.get(), ledger.createSync(inserted.get(), "EMAIL_CONFIRM"));
            limits.boundWaits(waits.get());
            var existing = jdbc.sql("""
                    SELECT id, status FROM subscription WHERE channel = 'EMAIL' AND dest_hmac = :dest AND status IN ('PENDING','ACTIVE') FOR UPDATE
                    """).param("dest", destination).query((rs, row) -> Map.entry(rs.getObject(1, UUID.class), rs.getString(2))).optional();
            if (existing.isEmpty()) continue;  // deleted between the two statements: try the insert once more
            if (existing.get().getValue().equals("ACTIVE")) return null;  // FR-A1: an active subscription is never overwritten
            UUID id = existing.get().getKey();
            jdbc.sql("""
                    UPDATE subscription SET confirm_token_hash = :hash, confirm_expires_at = :expires, conditions = CAST(:conditions AS jsonb),
                      notify_new = :new, notify_deadline = :deadline, consent_version = :consent, consented_at = :now, updated_at = :now
                    WHERE id = :id
                    """).param("hash", hash).param("expires", now.plus(CONFIRM_TTL)).param("conditions", json(conditions))
                    .param("new", conditions.notifyNew()).param("deadline", conditions.notifyDeadline())
                    .param("consent", properties.privacy().policyVersion()).param("now", now).param("id", id).update();
            return new Signup(id, ledger.createSync(id, "EMAIL_CONFIRM"));
        }
        return null;
    }

    private SyncSender.MailJob confirmJob(UUID id, byte[] hash, String email, String token) {
        String link = baseUrl + "/subscriptions/confirm?t=" + token;
        byte[] payload = mail(email, "[공채벨] 알림 신청을 확인해 주세요", """
                공채벨 채용공고 알림 신청을 받았습니다.

                아래 링크를 열고 '신청 확인' 버튼을 눌러야 알림이 시작됩니다. 링크는 24시간 동안 한 번만 쓸 수 있습니다.
                %s

                직접 신청하지 않았다면 이 메일을 무시하세요. 확인하지 않은 신청은 24시간 뒤 삭제됩니다.
                """.formatted(link));
        return new SyncSender.MailJob() {
            @Override public boolean stillEligible(JdbcClient db) {
                return db.sql("""
                        SELECT 1 FROM subscription WHERE id = :id AND status = 'PENDING' AND confirm_token_hash = :hash
                        AND confirm_expires_at > :now FOR SHARE
                        """).param("id", id).param("hash", hash).param("now", now()).query(Integer.class).optional().isPresent();
            }
            @Override public String cancelReason() { return "SUPERSEDED"; }
            @Override public byte[] payload() { return payload; }
        };
    }

    /** FR-A1: only a valid confirm POST activates (the token is consumed in the same statement). A SENT mail does not. */
    public Result confirm(String token, String ip) {
        byte[] ipKey = crypto.limitKey("TOKEN_FAIL:ip", ip);
        if (blocked(ipKey)) return Result.of(State.BLOCKED);
        if (token != null && token.length() <= 100) {
            OffsetDateTime now = now();
            var activated = tx.execute(status -> jdbc.sql("""
                    UPDATE subscription SET status = 'ACTIVE', confirmed_at = :now, confirm_token_hash = NULL,
                      confirm_expires_at = NULL, updated_at = :now
                    WHERE confirm_token_hash = :hash AND status = 'PENDING' AND confirm_expires_at > :now
                    RETURNING id, token_version
                    """).param("now", now).param("hash", AlertsCrypto.sha256(token))
                    .query((rs, row) -> crypto.capability(AlertsCrypto.Purpose.MANAGE, rs.getObject(1, UUID.class), rs.getInt(2))).optional());
            if (activated != null && activated.isPresent()) return new Result(State.CONFIRMED, activated.get());
        }
        limits.hit(RateLimiter.Scope.TOKEN_FAIL, ipKey, RateLimiter.TOKEN_FAIL);
        return Result.of(State.INVALID);
    }

    // ---- manage, update, unsubscribe ----

    public ManageAccess manage(String token, String ip) {
        byte[] ipKey = crypto.limitKey("TOKEN_FAIL:ip", ip);
        if (blocked(ipKey)) return new ManageAccess(State.BLOCKED, null);
        record Row(int version, ManageView view) {}
        Optional<ManageView> view = AlertsCrypto.claimedId(token).flatMap(id -> jdbc.sql("""
                SELECT channel, status, email, conditions::text, notify_new, notify_deadline, token_version FROM subscription WHERE id = :id
                """).param("id", id).query((rs, row) -> new Row(rs.getInt(7), new ManageView(id, rs.getString(1), rs.getString(2),
                        rs.getString(3) == null ? "Discord 웹훅" : mask(rs.getString(3)),
                        conditionsOf(rs.getString(4), rs.getBoolean(5), rs.getBoolean(6)), token))).optional()
                .filter(row -> crypto.verify(AlertsCrypto.Purpose.MANAGE, id, row.version(), token)).map(Row::view));
        if (view.isPresent()) return new ManageAccess(State.FOUND, view.get());
        limits.hit(RateLimiter.Scope.TOKEN_FAIL, ipKey, RateLimiter.TOKEN_FAIL);
        return new ManageAccess(State.INVALID, null);
    }

    /** FR-A3 condition change. Takes the subscription FOR UPDATE, so it serializes with a send holding FOR SHARE. */
    public State update(String token, Conditions conditions, String ip) {
        byte[] ipKey = crypto.limitKey("TOKEN_FAIL:ip", ip);
        if (blocked(ipKey)) return State.BLOCKED;
        State state = AlertsCrypto.claimedId(token).map(id -> tx.execute(status -> {
            var row = lockForToken(id);
            if (row.isEmpty() || !crypto.verify(AlertsCrypto.Purpose.MANAGE, id, row.get().getKey(), token)) return State.INVALID;
            if (!row.get().getValue().equals("ACTIVE")) return State.NOT_ACTIVE;
            jdbc.sql("""
                    UPDATE subscription SET conditions = CAST(:conditions AS jsonb), notify_new = :new, notify_deadline = :deadline,
                      updated_at = :now WHERE id = :id
                    """).param("conditions", json(conditions)).param("new", conditions.notifyNew())
                    .param("deadline", conditions.notifyDeadline()).param("now", now()).param("id", id).update();
            return State.UPDATED;
        })).orElse(State.INVALID);
        if (state == State.INVALID) limits.hit(RateLimiter.Scope.TOKEN_FAIL, ipKey, RateLimiter.TOKEN_FAIL);
        return state;
    }

    /**
     * FR-A3 / ADR-0013 unsubscribe: under the row lock, the erasure line is appended and fsynced, then the row is
     * deleted (its jobs, attempts and items cascade). If the journal write fails nothing is deleted.
     */
    public State unsubscribe(String token, AlertsCrypto.Purpose purpose, String ip) {
        byte[] ipKey = crypto.limitKey("TOKEN_FAIL:ip", ip);
        if (blocked(ipKey)) return State.BLOCKED;
        State state = AlertsCrypto.claimedId(token).map(id -> tx.execute(status -> {
            var row = lockForToken(id);
            if (row.isEmpty() || !crypto.verify(purpose, id, row.get().getKey(), token)) return State.INVALID;
            try { journal.append(id, clock.instant()); }
            catch (IOException failed) { throw new UncheckedIOException("ERASURE_LOG_WRITE_FAILED", failed); }
            jdbc.sql("DELETE FROM subscription WHERE id = :id").param("id", id).update();
            return State.UNSUBSCRIBED;
        })).orElse(State.INVALID);
        if (state == State.INVALID) limits.hit(RateLimiter.Scope.TOKEN_FAIL, ipKey, RateLimiter.TOKEN_FAIL);
        return state;
    }

    private Optional<Map.Entry<Integer, String>> lockForToken(UUID id) {
        return jdbc.sql("SELECT token_version, status FROM subscription WHERE id = :id FOR UPDATE").param("id", id)
                .query((rs, row) -> Map.entry(rs.getInt(1), rs.getString(2))).optional();
    }

    private boolean blocked(byte[] ipKey) {
        return limits.count(RateLimiter.Scope.TOKEN_FAIL, ipKey, RateLimiter.TOKEN_FAIL) >= RateLimiter.TOKEN_FAIL.max();
    }

    // ---- recovery ----

    /**
     * FR-A3: mails the management link to an active address. Always ACCEPTED (or RATE_LIMITED). The parent row is
     * held FOR KEY SHARE from the read to the job insert, which serializes the read with an unsubscribe (FOR UPDATE,
     * then DELETE). The unsubscribe either waits for this transaction, or commits first and the read finds no row.
     * A status or token change in between is caught by the eligibility check in A. Budget as in {@link #subscribeEmail}.
     */
    public State recoverEmail(String rawEmail, String ip) {
        long start = sender.start();
        String email = email(rawEmail);
        Supplier<Duration> waits = () -> sender.waitLimit(start);
        if (!limits.acquire(RateLimiter.Scope.RESEND, crypto.limitKey("RESEND:ip", ip), RateLimiter.PER_IP, waits)
                || !limits.acquire(RateLimiter.Scope.RESEND, crypto.limitKey("RESEND:email", email), RateLimiter.PER_TARGET, waits))
            return State.RATE_LIMITED;
        byte[] destination = crypto.destination("EMAIL", email);
        record Recovery(UUID id, int version, DeliveryLedger.Claim claim) {}
        Recovery recovery = tx.execute(status -> {
            limits.boundWaits(waits.get());
            return jdbc.sql("""
                    SELECT id, token_version FROM subscription WHERE channel = 'EMAIL' AND dest_hmac = :dest AND status = 'ACTIVE'
                    FOR KEY SHARE
                    """).param("dest", destination).query((rs, row) -> Map.entry(rs.getObject(1, UUID.class), rs.getInt(2))).optional()
                    .map(row -> new Recovery(row.getKey(), row.getValue(), ledger.createSync(row.getKey(), "EMAIL_RECOVER"))).orElse(null);
        });
        if (recovery != null) deliver(recovery.claim(), recoverJob(recovery.id(), recovery.version(), email), start);
        return State.ACCEPTED;
    }

    private SyncSender.MailJob recoverJob(UUID id, int version, String email) {
        byte[] payload = mail(email, "[공채벨] 구독 관리 링크", """
                요청하신 공채벨 알림 구독 관리 링크입니다.

                조건 수정·해지: %s
                바로 해지: %s

                링크를 가진 사람은 이 구독을 수정하거나 해지할 수 있으니 공유하지 마세요. 요청하지 않았다면 이 메일을 무시하세요.
                """.formatted(manageLink(id, version), baseUrl + "/subscriptions/unsubscribe?t=" + crypto.capability(AlertsCrypto.Purpose.UNSUBSCRIBE, id, version)));
        return new SyncSender.MailJob() {
            @Override public boolean stillEligible(JdbcClient db) {
                return db.sql("""
                        SELECT 1 FROM subscription WHERE id = :id AND status = 'ACTIVE' AND token_version = :version AND email = :email FOR SHARE
                        """).param("id", id).param("version", version).param("email", email).query(Integer.class).optional().isPresent();
            }
            @Override public String cancelReason() { return "NOT_ACTIVE"; }
            @Override public byte[] payload() { return payload; }
        };
    }

    /** FR-A3: the webhook URL itself is the credential; a hash match shows the management link once. */
    public Result recoverDiscord(String rawUrl, String ip) {
        if (!limits.acquire(RateLimiter.Scope.RESEND, crypto.limitKey("RESEND:ip", ip), RateLimiter.PER_IP)) return Result.of(State.RATE_LIMITED);
        WebhookUrl url = WebhookUrl.parse(rawUrl).orElseThrow(() -> new InvalidInput("webhook"));
        if (!limits.acquire(RateLimiter.Scope.RESEND, crypto.limitKey("RESEND:webhook", url.normalized()), RateLimiter.PER_TARGET))
            return Result.of(State.RATE_LIMITED);
        return jdbc.sql("""
                SELECT id, token_version FROM subscription WHERE channel = 'DISCORD' AND dest_hmac = :dest AND status IN ('PENDING','ACTIVE')
                """).param("dest", crypto.destination("DISCORD", url.normalized()))
                .query((rs, row) -> new Result(State.FOUND, crypto.capability(AlertsCrypto.Purpose.MANAGE, rs.getObject(1, UUID.class), rs.getInt(2))))
                .optional().orElse(Result.of(State.NONE));
    }

    // ---- Discord ----

    /**
     * FR-A2: validated URL, limits, no test for an already-live destination, then the in-request test (each attempt
     * charged to the Discord hourly total first). Only a 2xx inserts the ACTIVE row together with the DISCORD_TEST
     * success record and its attempts; a failed test stores nothing about the webhook. Budget as in
     * {@link #subscribeEmail}; the final insert gets the CLEANUP share.
     */
    public Result registerDiscord(String rawUrl, Conditions conditions, boolean agreed, boolean age14, String ip) {
        long start = sender.start();
        consent(agreed, age14);
        WebhookUrl url = WebhookUrl.parse(rawUrl).orElseThrow(() -> new InvalidInput("webhook"));
        String target = url.normalized();
        Supplier<Duration> waits = () -> sender.waitLimit(start);
        if (!limits.acquire(RateLimiter.Scope.SUBSCRIBE, crypto.limitKey("SUBSCRIBE:ip", ip), RateLimiter.PER_IP, waits)
                || !limits.acquire(RateLimiter.Scope.SUBSCRIBE, crypto.limitKey("SUBSCRIBE:webhook", target), RateLimiter.PER_TARGET, waits)
                || !limits.acquire(RateLimiter.Scope.WEBHOOK_TEST, crypto.limitKey("WEBHOOK_TEST:ip", ip), RateLimiter.PER_IP, waits)
                || !limits.acquire(RateLimiter.Scope.WEBHOOK_TEST, crypto.limitKey("WEBHOOK_TEST:webhook", target), RateLimiter.PER_TARGET, waits))
            return Result.of(State.RATE_LIMITED);
        byte[] destination = crypto.destination("DISCORD", target);
        if (liveDestination(destination, waits)) return Result.of(State.ALREADY);
        List<SyncSender.WebhookAttempt> attempts = sender.testWebhook(url, testMessage(), start);
        SendOutcome last = attempts.isEmpty() ? null : attempts.getLast().result().outcome();
        if (last != SendOutcome.SUCCESS)
            return Result.of(last == SendOutcome.WEBHOOK_404 ? State.WEBHOOK_NOT_FOUND : State.TEST_FAILED);
        UUID id = UUID.randomUUID();
        OffsetDateTime now = now();
        Boolean inserted = tx.execute(status -> {
            limits.boundWaits(SyncSender.CLEANUP);
            boolean created = jdbc.sql("""
                    INSERT INTO subscription(id, channel, status, webhook_enc, webhook_key_id, dest_hmac, conditions, notify_new,
                      notify_deadline, consent_version, consented_at, age_confirmed, confirmed_at, created_at, updated_at)
                    VALUES (:id, 'DISCORD', 'ACTIVE', :enc, :keyId, :dest, CAST(:conditions AS jsonb), :new, :deadline,
                      :consent, :now, true, :now, :now, :now)
                    ON CONFLICT (channel, dest_hmac) WHERE status IN ('PENDING','ACTIVE') DO NOTHING RETURNING id
                    """).param("id", id).param("enc", crypto.encryptWebhook(id, target)).param("keyId", crypto.webhookKeyId())
                    .param("dest", destination).param("conditions", json(conditions)).param("new", conditions.notifyNew())
                    .param("deadline", conditions.notifyDeadline()).param("consent", properties.privacy().policyVersion())
                    .param("now", now).query(UUID.class).optional().isPresent();
            if (!created) return false;  // a concurrent registration of the same webhook won
            long notification = jdbc.sql("""
                    INSERT INTO notification(subscription_id, kind, batch_key, status, attempts, created_at, finished_at, idempotency_key)
                    VALUES (:id, 'DISCORD_TEST', :batch, 'SENT', :attempts, :now, :now, :key) RETURNING id
                    """).param("id", id).param("batch", UUID.randomUUID().toString()).param("attempts", attempts.size())
                    .param("now", now).param("key", UUID.randomUUID()).query(Long.class).single();
            for (SyncSender.WebhookAttempt attempt : attempts)
                jdbc.sql("""
                        INSERT INTO notification_attempt(notification_id, attempt_no, started_at, finished_at, outcome, http_status)
                        VALUES (:notification, :no, :now, :now, :outcome, :status)
                        """).param("notification", notification).param("no", attempt.attemptNo()).param("now", now)
                        .param("outcome", attempt.result().outcome().name()).param("status", attempt.result().httpStatus()).update();
            return true;
        });
        return Boolean.TRUE.equals(inserted) ? new Result(State.REGISTERED, crypto.capability(AlertsCrypto.Purpose.MANAGE, id, 1))
                : Result.of(State.ALREADY);
    }

    private boolean liveDestination(byte[] destination, Supplier<Duration> waits) {
        return Boolean.TRUE.equals(tx.execute(status -> {
            limits.boundWaits(waits.get());
            return jdbc.sql("""
                    SELECT EXISTS (SELECT 1 FROM subscription WHERE channel = 'DISCORD' AND dest_hmac = :dest AND status IN ('PENDING','ACTIVE'))
                    """).param("dest", destination).query(Boolean.class).single();
        }));
    }

    private static byte[] testMessage() {
        ObjectNode body = JSON.createObjectNode();
        body.put("content", "공채벨 알림 연결 테스트입니다. 등록이 끝나면 이 채널로 조건에 맞는 채용공고 알림이 옵니다.");
        body.putObject("allowed_mentions").putArray("parse");
        return JSON.writeValueAsString(body).getBytes(UTF_8);
    }

    // ---- helpers ----

    public String manageLink(UUID id, int version) {
        return baseUrl + "/subscriptions/manage?t=" + crypto.capability(AlertsCrypto.Purpose.MANAGE, id, version);
    }

    /** A provider failure never changes the fixed response; the job row and its attempts carry the outcome. */
    private void deliver(DeliveryLedger.Claim claim, SyncSender.MailJob job, long start) {
        try { sender.deliverMail(claim, job, start); }
        catch (RuntimeException failure) { log.warn("ALERT_SYNC_SEND_ABORTED {}", failure.getClass().getSimpleName()); }
    }

    /** Resend request body. Plain text only: no tracking pixel or rewritten links (Sol privacy review). */
    private byte[] mail(String to, String subject, String text) {
        ObjectNode body = JSON.createObjectNode();
        body.put("from", properties.mail().from());
        body.putArray("to").add(to);
        body.put("subject", subject);
        body.put("text", text);
        return JSON.writeValueAsString(body).getBytes(UTF_8);
    }

    private static String json(Conditions conditions) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("ncs", conditions.ncs());
        map.put("region", conditions.region());
        map.put("hire", conditions.hire());
        map.put("career", conditions.career());
        map.put("q", conditions.q());
        return JSON.writeValueAsString(map);
    }

    private static Conditions conditionsOf(String stored, boolean notifyNew, boolean notifyDeadline) {
        var node = JSON.readTree(stored);
        return new Conditions(text(node, "ncs"), text(node, "region"), text(node, "hire"), text(node, "career"), text(node, "q"),
                notifyNew, notifyDeadline);
    }

    private static String text(tools.jackson.databind.JsonNode node, String field) {
        var value = node.path(field);
        return value.isNull() || value.isMissingNode() ? null : value.asString();
    }

    static String mask(String email) {
        int at = email.indexOf('@');
        return at <= 1 ? "***" + email.substring(Math.max(at, 0)) : email.charAt(0) + "***" + email.substring(at);
    }

    private OffsetDateTime now() { return OffsetDateTime.now(clock).truncatedTo(ChronoUnit.MICROS); }
}
