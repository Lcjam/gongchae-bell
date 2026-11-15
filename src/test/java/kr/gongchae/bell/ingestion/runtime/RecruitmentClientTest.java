package kr.gongchae.bell.ingestion.runtime;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RecruitmentClientTest {
    HttpServer server;
    HttpClient http;
    ApiBudget budget;
    List<Duration> delays;
    final AtomicInteger requests=new AtomicInteger();
    String query;
    @BeforeEach void setup() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        server.start(); http=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        budget=mock(ApiBudget.class); when(budget.reserve(anyString(),anyInt())).thenReturn(1L,2L,3L);
        delays=new ArrayList<>();
    }
    @AfterEach void stop() { server.stop(0); http.close(); }
    RecruitmentClient client(Duration timeout) {return client(timeout,Clock.systemUTC());}
    RecruitmentClient client(Duration timeout,Clock clock) {
        return new RecruitmentClient(http,budget,"fixture+/= key",URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/"),
                delays::add,clock,timeout);
    }
    void responds(int status,String body) {
        server.createContext("/",e->{ requests.incrementAndGet(); query=e.getRequestURI().getRawQuery();
            byte[] bytes=body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            e.sendResponseHeaders(status,bytes.length); e.getResponseBody().write(bytes); e.close(); });
    }
    @Test void regularHasOneFixedPageAndEncodesRawKeyExactlyOnce() {
        responds(200,"fixture-body");
        assertThat(client(Duration.ofSeconds(1)).regularList()).isEqualTo("fixture-body");
        assertThat(requests).hasValue(1);
        assertThat(query).contains("serviceKey=fixture%2B%2F%3D+key","resultType=json","ongoingYn=Y","pageNo=1","numOfRows=1000");
        verify(budget).reserve("REGULAR",1); verify(budget).finish(1,"SUCCESS");
    }
    @Test void encodingOrDecodingFormOfTheKeyGoesOnTheWireEncodedExactlyOnce() {
        responds(200,"fixture-body");
        for(String configured:new String[]{"fixture+/= key","fixture%2B%2F%3D%20key","fixture%2b%2f%3d%20key"}) {
            var key=new CollectorProperties(true,configured,0,0,false,null,null,null,false).serviceKey();
            new RecruitmentClient(http,budget,key,URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/"),delays::add,Clock.systemUTC(),
                    Duration.ofSeconds(1)).regularList();
            assertThat(query).as(configured).startsWith("serviceKey=fixture%2B%2F%3D+key&");
        }
    }
    @Test void backfillSendsOnlyTheStartLowerBoundAndNeverRequestsDetails() {
        responds(200,"fixture-body");
        client(Duration.ofSeconds(1)).backfillList(LocalDate.of(2023,11,15),2);
        // pbancEndYmd would filter by deadline upstream and drop postings that end after it.
        assertThat(query).contains("pbancBgngYmd=2023-11-15","numOfRows=1000","pageNo=2").doesNotContain("pbancEndYmd","ongoingYn");
        verify(budget).reserve("BACKFILL",1);
    }
    @Test void retriesAtMostThreeTimesAndEveryAttemptReservesBudget() {
        responds(503,"sensitive-provider-message");
        assertThatThrownBy(()->client(Duration.ofSeconds(1)).regularList())
                .isInstanceOf(CollectionFailure.class).hasMessage("HTTP_STATUS");
        assertThat(requests).hasValue(3); assertThat(delays).containsExactly(Duration.ofSeconds(2),Duration.ofSeconds(4));
        verify(budget,times(3)).reserve(eq("REGULAR"),anyInt());
        verify(budget).finish(1,"HTTP_STATUS"); verify(budget).finish(2,"HTTP_STATUS"); verify(budget).finish(3,"HTTP_STATUS");
    }
    @Test void rateLimitUsesServerDelay() {
        server.createContext("/",e->{ requests.incrementAndGet(); e.getResponseHeaders().add("Retry-After","7");
            e.sendResponseHeaders(429,-1);e.close(); });
        assertThatThrownBy(()->client(Duration.ofSeconds(1)).detail(42)).hasMessage("HTTP_RATE_LIMIT");
        assertThat(delays).containsExactly(Duration.ofSeconds(7),Duration.ofSeconds(7));
        verify(budget,times(3)).reserve(eq("DETAIL"),anyInt());
    }
    @Test void rateLimitWaitsOnlyForShortReadableDelaysAndNeverRetriesEarlierThanAsked() {
        var now=Instant.parse("2026-11-15T06:00:00Z");
        var header=new java.util.concurrent.atomic.AtomicReference<String>();
        server.createContext("/",e->{ requests.incrementAndGet(); if(header.get()!=null) e.getResponseHeaders().add("Retry-After",header.get());
            e.sendResponseHeaders(429,-1);e.close(); });
        java.util.function.Function<Long,String> date=s->java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.format(now.plusSeconds(s).atOffset(java.time.ZoneOffset.UTC));
        var waits=new java.util.LinkedHashMap<String,Duration>();
        waits.put("60",Duration.ofSeconds(60));
        waits.put(date.apply(30L),Duration.ofSeconds(30));
        waits.put(date.apply(-30L),Duration.ZERO);
        waits.put(null,null);
        for(var c:waits.entrySet()) {
            header.set(c.getKey()); requests.set(0); delays.clear();
            assertThatThrownBy(()->client(Duration.ofSeconds(1),Clock.fixed(now,java.time.ZoneOffset.UTC)).detail(42)).hasMessage("HTTP_RATE_LIMIT");
            assertThat(requests).as(c.getKey()).hasValue(3);
            if(c.getValue()==null) assertThat(delays).as("no header: normal backoff").containsExactly(Duration.ofSeconds(2),Duration.ofSeconds(4));
            else assertThat(delays).as(c.getKey()).containsExactly(c.getValue(),c.getValue());
        }
        for(String tooLongOrUnreadable:new String[]{"61","86400","99999999999999999999",date.apply(61L),"-5","+5","soon",""}) {
            header.set(tooLongOrUnreadable); requests.set(0); delays.clear();
            assertThatThrownBy(()->client(Duration.ofSeconds(1),Clock.fixed(now,java.time.ZoneOffset.UTC)).detail(42))
                    .as(tooLongOrUnreadable).hasMessage("HTTP_RATE_LIMIT");
            assertThat(requests).as(tooLongOrUnreadable).hasValue(1);
            assertThat(delays).as(tooLongOrUnreadable).isEmpty();
        }
    }
    @Test void redirectsAndOtherClientErrorsAreNeverRetriedOrFollowed() {
        server.createContext("/",e->{ requests.incrementAndGet(); e.getResponseHeaders().add("Location","/stolen");
            e.sendResponseHeaders(302,-1);e.close(); });
        assertThatThrownBy(()->client(Duration.ofSeconds(1)).regularList()).hasMessage("HTTP_STATUS");
        assertThat(requests).hasValue(1); assertThat(delays).isEmpty();
    }
    @Test void budgetExhaustionMakesNoExternalRequest() {
        responds(200,"body"); when(budget.reserve(eq("REGULAR"),anyInt())).thenThrow(new CollectionFailure(CollectionFailure.Code.BUDGET_EXHAUSTED));
        assertThatThrownBy(()->client(Duration.ofSeconds(1)).regularList()).hasMessage("BUDGET_EXHAUSTED");
        assertThat(requests).hasValue(0);
    }
    @Test void requestTimeoutAlsoBoundsBodyAfterHeadersArrive() {
        server.createContext("/",e->{requests.incrementAndGet();e.sendResponseHeaders(200,1);e.getResponseBody().flush();
            try {Thread.sleep(300);} catch(InterruptedException ignored) {}
            try {e.getResponseBody().write('x');} finally {e.close();} });
        assertThatThrownBy(()->client(Duration.ofMillis(60)).regularList()).hasMessage("HTTP_TIMEOUT");
        verify(budget,times(3)).reserve(eq("REGULAR"),anyInt());
    }
    @Test void requestTimeoutIsBoundedAndExceptionContainsOnlyFixedCode() {
        server.createContext("/",e->{ requests.incrementAndGet(); try {Thread.sleep(150);} catch(InterruptedException ignored) {}
            try {e.sendResponseHeaders(200,-1);} finally {e.close();} });
        assertThatThrownBy(()->client(Duration.ofMillis(30)).regularList()).hasMessage("HTTP_TIMEOUT");
        verify(budget,times(3)).reserve(eq("REGULAR"),anyInt());
    }
}
