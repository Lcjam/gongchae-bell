package kr.gongchae.bell.ingestion.runtime;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class RecruitmentClient {
    private static final URI OFFICIAL = URI.create("https://apis.data.go.kr/1051000/recruitment/");
    /** Longest Retry-After we sleep through; a longer one ends the run so the next one retries (never earlier). */
    static final Duration MAX_INLINE_WAIT = Duration.ofSeconds(60);
    private final HttpClient http;
    private final ApiBudget budget;
    private final String serviceKey;
    private final URI base;
    private final Sleeper sleeper;
    private final Clock clock;
    private final Duration timeout;
    @org.springframework.beans.factory.annotation.Autowired
    public RecruitmentClient(ApiBudget budget, CollectorProperties properties, Clock clock) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build(),
                budget, properties.serviceKey(), OFFICIAL, Thread::sleep, clock, Duration.ofSeconds(10));
    }
    RecruitmentClient(HttpClient http, ApiBudget budget, String serviceKey, URI base, Sleeper sleeper, Clock clock, Duration timeout) {
        this.http=http; this.budget=budget; this.serviceKey=serviceKey; this.base=base;
        this.sleeper=sleeper; this.clock=clock; this.timeout=timeout;
    }
    public String regularList() { return get("list", "REGULAR", Map.of("ongoingYn","Y","numOfRows","1000","pageNo","1")); }
    /** Start-date lower bound only: the service applies pbancEndYmd to the deadline and would drop postings (routes.md). */
    public String backfillList(LocalDate from, int page) {
        return get("list", "BACKFILL", Map.of("pbancBgngYmd",from.toString(),"numOfRows","1000","pageNo",Integer.toString(page)));
    }
    public String detail(long sn) { return get("detail","DETAIL", Map.of("sn",Long.toString(sn))); }
    private String get(String endpoint, String kind, Map<String,String> params) {
        Map<String,String> query = new LinkedHashMap<>();
        query.put("serviceKey",serviceKey); query.put("resultType","json"); query.putAll(params);
        String encoded = query.entrySet().stream().map(e -> encode(e.getKey())+"="+encode(e.getValue()))
                .collect(java.util.stream.Collectors.joining("&"));
        URI uri = base.resolve(endpoint + "?" + encoded);
        CollectionFailure.Code last = CollectionFailure.Code.HTTP_CONNECTION;
        for (int attempt=0; attempt<3; attempt++) {
            long id = budget.reserve(kind,attempt+1);
            Duration delay = Duration.ofSeconds(2L << attempt);
            try {
                var response=send(HttpRequest.newBuilder(uri).timeout(timeout).GET().build());
                int code=response.statusCode();
                if (code==200) { budget.finish(id,"SUCCESS"); return response.body(); }
                last=code==429 ? CollectionFailure.Code.HTTP_RATE_LIMIT : CollectionFailure.Code.HTTP_STATUS;
                budget.finish(id,last.name());
                if (code==429) delay=retryAfter(response.headers().firstValue("Retry-After"),delay)
                        .orElseThrow(() -> new CollectionFailure(CollectionFailure.Code.HTTP_RATE_LIMIT));
                else if (code<500 || code>599) throw new CollectionFailure(last);
            } catch (HttpTimeoutException e) {
                last=CollectionFailure.Code.HTTP_TIMEOUT; budget.finish(id,last.name());
            } catch (IOException e) {
                last=CollectionFailure.Code.HTTP_CONNECTION; budget.finish(id,last.name());
            } catch (InterruptedException e) {
                budget.finish(id,CollectionFailure.Code.INTERRUPTED.name()); Thread.currentThread().interrupt();
                throw new CollectionFailure(CollectionFailure.Code.INTERRUPTED);
            }
            if (attempt<2) {
                try { sleeper.sleep(delay); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new CollectionFailure(CollectionFailure.Code.INTERRUPTED); }
            }
        }
        throw new CollectionFailure(last);
    }
    private HttpResponse<String> send(HttpRequest request) throws IOException,InterruptedException {
        var response=http.sendAsync(request,HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        try {
            // HttpRequest.timeout alone does not bound a slow response body.
            return response.get(timeout.toNanos(),java.util.concurrent.TimeUnit.NANOSECONDS);
        } catch(java.util.concurrent.TimeoutException expired) {
            response.cancel(true);throw new HttpTimeoutException("HTTP_TIMEOUT");
        } catch(java.util.concurrent.ExecutionException failed) {
            if(failed.getCause() instanceof IOException io) throw io;
            throw new IOException("HTTP_CONNECTION");
        } catch(InterruptedException interrupted) {
            response.cancel(true);throw interrupted;
        }
    }
    /** Empty when the server asks for more than MAX_INLINE_WAIT or says something unreadable: we cannot honour it inline. */
    Optional<Duration> retryAfter(Optional<String> header, Duration fallback) {
        if (header.isEmpty()) return Optional.of(fallback);
        String value=header.get().trim();
        Duration wait;
        try {
            wait = value.matches("\\d+") ? Duration.ofSeconds(Long.parseLong(value))
                    : Duration.between(clock.instant(),ZonedDateTime.parse(value,DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
        } catch (RuntimeException unreadable) { return Optional.empty(); }
        if (wait.isNegative()) return Optional.of(Duration.ZERO);
        return wait.compareTo(MAX_INLINE_WAIT) > 0 ? Optional.empty() : Optional.of(wait);
    }
    private static String encode(String value) { return URLEncoder.encode(value,StandardCharsets.UTF_8); }
    @FunctionalInterface interface Sleeper { void sleep(Duration duration) throws InterruptedException; }
}
