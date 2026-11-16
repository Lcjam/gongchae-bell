package kr.gongchae.bell.alerts;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Real JDK HttpClient against loopback stubs; no real provider is contacted. */
class TransportTest {
    private HttpServer server;
    private final AtomicInteger hits = new AtomicInteger();
    private final AtomicReference<String> headers = new AtomicReference<>();
    private final AtomicReference<String> body = new AtomicReference<>();

    private URI serve(int status, String response, String extraHeader, long delayMillis) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            hits.incrementAndGet();
            headers.set(exchange.getRequestHeaders().getFirst("Authorization") + "|" + exchange.getRequestHeaders().getFirst("Idempotency-Key")
                    + "|" + exchange.getRequestHeaders().getFirst("Content-Type"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), UTF_8));
            try { Thread.sleep(delayMillis); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            if (extraHeader != null) exchange.getResponseHeaders().set(extraHeader.split(":")[0], extraHeader.split(":")[1]);
            byte[] bytes = response.getBytes(UTF_8);
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
    }

    @AfterEach void stop() { if (server != null) { server.stop(0); server = null; } }

    private SendOutcome.Result resend(URI base, Duration timeout) {
        return new HttpTransports.Resend(HttpTransports.client(), base.resolve("emails"), "re_fixture").send("{\"a\":1}".getBytes(UTF_8),
                UUID.fromString("00000000-0000-0000-0000-000000000001"), timeout);
    }

    @Test
    void resendSendsTheKeyHeaderAndClassifiesResponsesWithoutKeepingBodies() throws Exception {
        assertThat(resend(serve(200, "{\"id\":\"x\"}", null, 0), Duration.ofSeconds(2)).outcome()).isEqualTo(SendOutcome.SUCCESS);
        assertThat(headers.get()).isEqualTo("Bearer re_fixture|00000000-0000-0000-0000-000000000001|application/json");
        assertThat(body.get()).isEqualTo("{\"a\":1}");
        stop();
        assertThat(resend(serve(429, "{\"name\":\"daily_quota_exceeded\"}", null, 0), Duration.ofSeconds(2)).outcome()).isEqualTo(SendOutcome.QUOTA_DAILY);
        stop();
        assertThat(resend(serve(429, "{\"name\":\"monthly_quota_exceeded\"}", null, 0), Duration.ofSeconds(2)).outcome()).isEqualTo(SendOutcome.QUOTA_MONTHLY);
        stop();
        var limited = resend(serve(429, "{\"name\":\"rate_limit_exceeded\"}", "retry-after:3", 0), Duration.ofSeconds(2));
        assertThat(limited.outcome()).isEqualTo(SendOutcome.RATE_LIMITED);
        assertThat(limited.retryAfter()).isEqualTo(Duration.ofSeconds(3));
        stop();
        assertThat(resend(serve(409, "{}", null, 0), Duration.ofSeconds(2)).outcome().unknown()).isTrue();
        stop();
        assertThat(resend(serve(503, "{}", null, 0), Duration.ofSeconds(2)).outcome()).isEqualTo(SendOutcome.HTTP_5XX);
        stop();
        assertThat(resend(serve(422, "{\"name\":\"validation_error\"}", null, 0), Duration.ofSeconds(2)).outcome()).isEqualTo(SendOutcome.REQUEST_4XX);
        stop();
        assertThat(resend(serve(403, "{}", null, 0), Duration.ofSeconds(2)).outcome()).isEqualTo(SendOutcome.ACCOUNT_4XX);
    }

    @Test
    void aSlowResponseIsAnUnknownTimeoutAndARefusedConnectionIsDefinite() throws Exception {
        var slow = resend(serve(200, "{}", null, 1500), Duration.ofMillis(300));
        assertThat(slow.outcome()).isEqualTo(SendOutcome.TIMEOUT);
        assertThat(slow.outcome().unknown()).isTrue();
        int closedPort;
        try (var socket = new ServerSocket(0)) { closedPort = socket.getLocalPort(); }
        var refused = resend(URI.create("http://127.0.0.1:" + closedPort + "/"), Duration.ofSeconds(2));
        assertThat(refused.outcome()).isEqualTo(SendOutcome.CONNECT_FAILED);
        assertThat(refused.outcome().unknown()).isFalse();
    }

    private SendOutcome.Result discord(URI target, AddressGuard guard) {
        var url = WebhookUrl.parse("https://discord.com/api/webhooks/1/token").orElseThrow();
        return new HttpTransports.Discord(HttpTransports.client(), guard, u -> target).send(url, "{}".getBytes(UTF_8), Duration.ofSeconds(2));
    }

    @Test
    void discordRefusesPrivateAddressesBeforeAnyRequestAndNeverFollowsRedirects() throws Exception {
        URI stub = serve(204, "", null, 0);
        var internal = new AddressGuard(host -> new InetAddress[]{InetAddress.getByName("10.0.0.5")});
        assertThat(discord(stub, internal).outcome()).isEqualTo(SendOutcome.BLOCKED_ADDRESS);
        assertThat(hits).hasValue(0);
        var open = new AddressGuard(host -> new InetAddress[]{InetAddress.getByName("162.159.135.232")});
        assertThat(discord(stub, open).outcome()).isEqualTo(SendOutcome.SUCCESS);
        stop();

        AtomicInteger followed = new AtomicInteger();
        HttpServer other = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        other.createContext("/", exchange -> { followed.incrementAndGet(); exchange.sendResponseHeaders(204, -1); exchange.close(); });
        other.start();
        try {
            URI redirect = serve(302, "", "Location:http://127.0.0.1:" + other.getAddress().getPort() + "/", 0);
            assertThat(discord(redirect, open).outcome()).isEqualTo(SendOutcome.REDIRECTED);
            assertThat(followed).hasValue(0);
        } finally { other.stop(0); }
        stop();
        var limited = discord(serve(429, "{\"retry_after\": 1.25, \"global\": false}", null, 0), open);
        assertThat(limited.outcome()).isEqualTo(SendOutcome.RATE_LIMITED);
        assertThat(limited.retryAfter()).isEqualTo(Duration.ofMillis(1250));
        stop();
        assertThat(discord(serve(404, "{\"code\":10015}", null, 0), open).outcome()).isEqualTo(SendOutcome.WEBHOOK_404);
        stop();
        assertThat(discord(serve(401, "{\"code\":50027}", null, 0), open).outcome()).isEqualTo(SendOutcome.WEBHOOK_401);
    }
}
