package kr.gongchae.bell.ops;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Success-only ping to an external heartbeat check (Healthchecks.io, ADR-0006). The external check alerts when pings
 * stop arriving, so a failed ping is only logged and never fails the job. The URL carries the check's secret id and is
 * never logged or put in an exception message.
 */
public class Heartbeat {
    private static final Logger log = LoggerFactory.getLogger(Heartbeat.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private final String name;
    private final URI url;
    private final HttpClient http;

    /** A blank url turns the heartbeat off, which is the default. */
    public Heartbeat(String name, String url) {
        this.name = name;
        this.url = url == null || url.isBlank() ? null : parse(url.strip());
        http = this.url == null ? null
                : HttpClient.newBuilder().connectTimeout(TIMEOUT).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    private static URI parse(String url) {
        try {
            var uri = new URI(url);
            if (("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) && uri.getHost() != null) return uri;
        } catch (URISyntaxException ignored) {
            // fall through: the message must not echo the URL
        }
        throw new IllegalArgumentException("INVALID_HEARTBEAT_URL");
    }

    public void ping() {
        if (url == null) return;
        try {
            int status = http.send(HttpRequest.newBuilder(url).timeout(TIMEOUT).GET().build(),
                    HttpResponse.BodyHandlers.discarding()).statusCode();
            if (status != 200) log.warn("HEARTBEAT_FAILED {} HTTP_{}", name, status);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("HEARTBEAT_FAILED {} INTERRUPTED", name);
        } catch (IOException | RuntimeException e) {
            log.warn("HEARTBEAT_FAILED {} {}", name, e.getClass().getSimpleName());
        }
    }
}
