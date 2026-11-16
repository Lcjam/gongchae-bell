package kr.gongchae.bell.alerts;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Resend and Discord over the JDK HttpClient (ADR-0007, ADR-0009). Redirects are never followed. */
final class HttpTransports {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private HttpTransports() {}

    static HttpClient client() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    /** The whole exchange (connect, headers and body) is bounded by {@code timeout}. */
    static Exchange exchange(HttpClient http, HttpRequest request, Duration timeout) {
        var future = http.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        try {
            return new Exchange(future.get(Math.max(1, timeout.toMillis()), TimeUnit.MILLISECONDS), null);
        } catch (TimeoutException late) {
            future.cancel(true);
            return new Exchange(null, SendOutcome.TIMEOUT);
        } catch (InterruptedException interrupted) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            return new Exchange(null, SendOutcome.IO_ERROR);
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof HttpConnectTimeoutException || cause instanceof ConnectException) return new Exchange(null, SendOutcome.CONNECT_FAILED);
            if (cause instanceof HttpTimeoutException) return new Exchange(null, SendOutcome.TIMEOUT);
            return new Exchange(null, SendOutcome.IO_ERROR);
        }
    }

    record Exchange(HttpResponse<String> response, SendOutcome failure) {}

    static JsonNode body(HttpResponse<String> response) {
        try { return JSON.readTree(response.body() == null ? "" : response.body()); }
        catch (RuntimeException notJson) { return JSON.createObjectNode(); }
    }

    static Duration seconds(String value) {
        if (value == null) return null;
        try {
            double seconds = Double.parseDouble(value.trim());
            return seconds >= 0 && seconds < 86_400 ? Duration.ofMillis((long) Math.ceil(seconds * 1000)) : null;
        } catch (NumberFormatException unreadable) { return null; }
    }

    /** POST https://api.resend.com/emails with Idempotency-Key (ADR-0007). */
    @Component
    static class Resend implements MailTransport {
        private final HttpClient http;
        private final URI endpoint;
        private final String apiKey;

        @Autowired
        Resend(AlertsProperties properties) {
            this(client(), URI.create(properties.mail().baseUrl()).resolve("emails"), properties.mail().apiKey());
        }

        Resend(HttpClient http, URI endpoint, String apiKey) { this.http = http; this.endpoint = endpoint; this.apiKey = apiKey; }

        @Override
        public SendOutcome.Result send(byte[] payload, UUID idempotencyKey, Duration timeout) {
            if (apiKey == null || apiKey.isBlank()) throw new IllegalStateException("ALERTS_DISABLED");
            HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(timeout)
                    .header("Authorization", "Bearer " + apiKey).header("Content-Type", "application/json")
                    .header("Idempotency-Key", idempotencyKey.toString())
                    .POST(HttpRequest.BodyPublishers.ofByteArray(payload)).build();
            Exchange exchange = exchange(http, request, timeout);
            if (exchange.failure() != null) return SendOutcome.Result.of(exchange.failure());
            int status = exchange.response().statusCode();
            if (status >= 200 && status < 300) return new SendOutcome.Result(SendOutcome.SUCCESS, status, null);
            if (status == 409) return new SendOutcome.Result(SendOutcome.HTTP_409, status, null);
            if (status == 429) {
                String name = body(exchange.response()).path("name").asString("");
                if (name.equals("daily_quota_exceeded")) return new SendOutcome.Result(SendOutcome.QUOTA_DAILY, status, null);
                if (name.equals("monthly_quota_exceeded")) return new SendOutcome.Result(SendOutcome.QUOTA_MONTHLY, status, null);
                return new SendOutcome.Result(SendOutcome.RATE_LIMITED, status,
                        seconds(exchange.response().headers().firstValue("retry-after").orElse(null)));
            }
            if (status == 401 || status == 403) return new SendOutcome.Result(SendOutcome.ACCOUNT_4XX, status, null);
            if (status >= 300 && status < 400) return new SendOutcome.Result(SendOutcome.REDIRECTED, status, null);
            if (status >= 400 && status < 500) return new SendOutcome.Result(SendOutcome.REQUEST_4XX, status, null);
            return new SendOutcome.Result(SendOutcome.HTTP_5XX, status, null);
        }
    }

    /** Discord Execute Webhook with wait=true. Every resolved address is checked right before the request. */
    @Component
    static class Discord implements WebhookTransport {
        private final HttpClient http;
        private final AddressGuard guard;
        private final Function<WebhookUrl, URI> target;

        @Autowired
        Discord() { this(client(), new AddressGuard(InetAddress::getAllByName), WebhookUrl::executeUri); }

        Discord(HttpClient http, AddressGuard guard, Function<WebhookUrl, URI> target) { this.http = http; this.guard = guard; this.target = target; }

        @Override
        public SendOutcome.Result send(WebhookUrl url, byte[] payload, Duration timeout) {
            if (!guard.allows(url.host())) return SendOutcome.Result.of(SendOutcome.BLOCKED_ADDRESS);
            HttpRequest request = HttpRequest.newBuilder(target.apply(url)).timeout(timeout)
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofByteArray(payload)).build();
            Exchange exchange = exchange(http, request, timeout);
            if (exchange.failure() != null) return SendOutcome.Result.of(exchange.failure());
            int status = exchange.response().statusCode();
            if (status >= 200 && status < 300) return new SendOutcome.Result(SendOutcome.SUCCESS, status, null);
            if (status == 404) return new SendOutcome.Result(SendOutcome.WEBHOOK_404, status, null);
            if (status == 401) return new SendOutcome.Result(SendOutcome.WEBHOOK_401, status, null);
            if (status == 429) {
                Duration wait = seconds(body(exchange.response()).path("retry_after").asString(null));
                if (wait == null) wait = seconds(exchange.response().headers().firstValue("retry-after").orElse(null));
                return new SendOutcome.Result(SendOutcome.RATE_LIMITED, status, wait);
            }
            if (status >= 300 && status < 400) return new SendOutcome.Result(SendOutcome.REDIRECTED, status, null);
            if (status >= 400 && status < 500) return new SendOutcome.Result(SendOutcome.REQUEST_4XX, status, null);
            return new SendOutcome.Result(SendOutcome.HTTP_5XX, status, null);
        }
    }
}
