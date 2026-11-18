package kr.gongchae.bell.alerts;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Success-only GET ping for the daily digest slot winner (Healthchecks.io, ADR-0006).
 * Matches the #15 Heartbeat contract: blank URL disables pinging, malformed URL throws INVALID_HEARTBEAT_URL
 * without echoing the URL, and ping failures only log without rolling back or failing the digest run.
 */
@Component("digestHeartbeat")
public class DigestHeartbeat {
    private static final Logger log = LoggerFactory.getLogger(DigestHeartbeat.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final URI url;
    private final HttpClient http;

    @Autowired
    public DigestHeartbeat(@Value("${heartbeat.digest-url:}") String url) {
        this.url = url == null || url.isBlank() ? null : parse(url.strip());
        this.http = this.url == null ? null
                : HttpClient.newBuilder().connectTimeout(TIMEOUT).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    private static URI parse(String url) {
        try {
            URI uri = new URI(url);
            if (("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) && uri.getHost() != null) {
                return uri;
            }
        } catch (URISyntaxException ignored) {
            // Never include the secret URL in error messages.
        }
        throw new IllegalArgumentException("INVALID_HEARTBEAT_URL");
    }

    public void ping() {
        if (url == null) return;
        try {
            int status = http.send(HttpRequest.newBuilder(url).timeout(TIMEOUT).GET().build(),
                    HttpResponse.BodyHandlers.discarding()).statusCode();
            if (status != 200) log.warn("HEARTBEAT_FAILED digest HTTP_{}", status);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("HEARTBEAT_FAILED digest INTERRUPTED");
        } catch (IOException | RuntimeException e) {
            log.warn("HEARTBEAT_FAILED digest {}", e.getClass().getSimpleName());
        }
    }
}
