package kr.gongchae.bell.ingestion.runtime;

import static org.assertj.core.api.Assertions.*;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class RuntimeDatabaseTest {
    @Container static PostgreSQLContainer postgres=new PostgreSQLContainer("postgres:17");
    JdbcClient jdbc;
    JdbcTransactionManager manager;
    DriverManagerDataSource dataSource;
    ApiBudget first;
    ApiBudget second;
    Clock clock=Clock.fixed(Instant.parse("2026-11-15T15:01:00Z"),ZoneOffset.UTC);
    @BeforeEach void setup() {
        var ds=new DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());
        dataSource=ds;
        Flyway.configure().dataSource(ds).load().migrate(); jdbc=JdbcClient.create(ds); manager=new JdbcTransactionManager(ds);
        jdbc.sql("TRUNCATE api_attempt, api_budget, collect_run RESTART IDENTITY CASCADE").update();
        var properties=new CollectorProperties(false,null,500,900,false,null,null,null,false);
        first=new ApiBudget(jdbc,manager,clock,properties); second=new ApiBudget(jdbc,manager,clock,properties);
    }
    String completeFixture() throws Exception {
        var json=tools.jackson.databind.json.JsonMapper.builder().build();
        var root=(tools.jackson.databind.node.ObjectNode)json.readTree(java.nio.file.Files.readString(java.nio.file.Path.of("fixtures/alio-public-list-sample.json"))).path("data");
        root.put("totalCount",root.path("result").size());
        for(var row:root.path("result")) {
            ((tools.jackson.databind.node.ObjectNode)row).put("pbancBgngYmd","20261101").put("pbancEndYmd","20261231");
        }
        return json.writeValueAsString(root);
    }
    @Test void twoCompleteRuntimeInstancesMakeOneExternalRequestAndOneActualCoreApply() throws Exception {
        jdbc.sql("TRUNCATE institution CASCADE").update();
        String body=completeFixture();
        var requests=new java.util.concurrent.atomic.AtomicInteger();
        var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",e->{requests.incrementAndGet();byte[] bytes=body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            e.sendResponseHeaders(200,bytes.length);e.getResponseBody().write(bytes);e.close();});server.start();
        try(var http=java.net.http.HttpClient.newHttpClient();var pool=Executors.newFixedThreadPool(2)) {
            var uri=java.net.URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/");
            var runs=new CollectionRuns(jdbc,clock,manager);
            var ingestor=new kr.gongchae.bell.ingestion.PostingIngestor(jdbc,manager,clock.withZone(ApiBudget.KST));
            var details=org.mockito.Mockito.mock(DetailCollector.class);
            var heartbeat=org.mockito.Mockito.mock(kr.gongchae.bell.ops.Heartbeat.class);
            var one=new RegularCollector(runs,new RecruitmentClient(http,first,"fixture",uri,d->{},clock,java.time.Duration.ofSeconds(2)),ingestor,details,heartbeat,clock);
            var two=new RegularCollector(runs,new RecruitmentClient(http,second,"fixture",uri,d->{},clock,java.time.Duration.ofSeconds(2)),ingestor,details,heartbeat,clock);
            var start=new CountDownLatch(1);
            var a=pool.submit(()->{start.await();return one.collect(clock.instant());});
            var b=pool.submit(()->{start.await();return two.collect(clock.instant());});start.countDown();
            assertThat((a.get()?1:0)+(b.get()?1:0)).isEqualTo(1);
            assertThat(requests).hasValue(1);
            assertThat(jdbc.sql("SELECT count(*) FROM posting").query(Integer.class).single()).isEqualTo(10);
            assertThat(jdbc.sql("SELECT result FROM collect_run WHERE kind='REGULAR'").query(String.class).single()).isEqualTo("COMPLETE");
            assertThat(jdbc.sql("SELECT count FROM api_budget").query(Integer.class).single()).isEqualTo(1);
            org.mockito.Mockito.verify(details).collect(RegularCollector.slot(clock.instant()));
            // Two apps, one slot: one heartbeat.
            org.mockito.Mockito.verify(heartbeat).ping();
        } finally {server.stop(0);}
    }
    @Test void regularHttpAndCoreFailuresKeepLastCompleteAndCoreOwnsParserFailureCode() throws Exception {
        jdbc.sql("TRUNCATE institution CASCADE").update();
        var runs=new CollectionRuns(jdbc,clock,manager);
        var client=org.mockito.Mockito.mock(RecruitmentClient.class);
        var ingestor=new kr.gongchae.bell.ingestion.PostingIngestor(jdbc,manager,clock.withZone(ApiBudget.KST));
        var details=org.mockito.Mockito.mock(DetailCollector.class);
        var heartbeat=org.mockito.Mockito.mock(kr.gongchae.bell.ops.Heartbeat.class);
        var collector=new RegularCollector(runs,client,ingestor,details,heartbeat,clock);
        org.mockito.Mockito.when(client.regularList()).thenReturn(completeFixture()).thenReturn("not-json fixture secret")
                .thenThrow(new CollectionFailure(CollectionFailure.Code.HTTP_TIMEOUT));
        assertThat(collector.collect(clock.instant())).isTrue();
        var last=jdbc.sql("SELECT max(finished_at)::text FROM collect_run WHERE result='COMPLETE'").query(String.class).single();
        assertThat(collector.collect(clock.instant().plusSeconds(3600))).isFalse();
        assertThat(collector.collect(clock.instant().plusSeconds(7200))).isFalse();
        assertThat(jdbc.sql("SELECT error FROM collect_run WHERE result='FAILED' ORDER BY id").query(String.class).list())
                .containsExactly("NON_JSON","HTTP_TIMEOUT");
        assertThat(jdbc.sql("SELECT max(finished_at)::text FROM collect_run WHERE result='COMPLETE'").query(String.class).single()).isEqualTo(last);
        assertThat(jdbc.sql("SELECT count(*) FROM posting").query(Integer.class).single()).isEqualTo(10);
        org.mockito.Mockito.verify(details).collect(RegularCollector.slot(clock.instant()));
        // Only the COMPLETE run pings; the failed ones stay silent so the external check notices the gap.
        org.mockito.Mockito.verify(heartbeat).ping();
        // Same slot again (a restart or the other color): not claimed, no second ping.
        assertThat(collector.collect(clock.instant())).isFalse();
        org.mockito.Mockito.verify(heartbeat).ping();
    }
    @Test void completedPageCheckpointKeepsExactIdsAndPeriodForResume() {
        var runs=new CollectionRuns(jdbc,clock,manager);
        var from=java.time.LocalDate.of(2024,2,1);var to=java.time.LocalDate.of(2024,2,29);
        long scan=runs.claim("BACKFILL","manual1/scan").orElseThrow();runs.metadata(scan,from,to,from,to,null,scan);
        long page=runs.claim("BACKFILL","manual1/scan/1").orElseThrow();runs.metadata(page,from,to,from,to,1,scan);
        assertThat(runs.latestScan(from,to)).hasValueSatisfying(s->{assertThat(s.generation()).isEqualTo(scan);assertThat(s.completed()).isNull();});
        runs.completePage(page,new CollectionRuns.Page(3,3,java.util.Set.of(5L,9L,12L),java.util.Set.of(5L,9L)),2,0,0);
        var resumed=runs.completedPage(scan,1).orElseThrow();
        assertThat(resumed.total()).isEqualTo(3);assertThat(resumed.received()).isEqualTo(3);
        assertThat(resumed.sns()).containsExactlyInAnyOrder(5L,9L,12L);assertThat(resumed.applied()).containsExactlyInAnyOrder(5L,9L);
        assertThat(runs.completedPage(scan+1000,1)).as("another generation").isEmpty();
        runs.completeScan(scan,resumed);
        assertThat(runs.latestScan(from,to)).hasValueSatisfying(s->{
            assertThat(s.unique()).isEqualTo(3);assertThat(s.applied()).isEqualTo(2);
            assertThat(s.completed().sns()).containsExactlyInAnyOrder(5L,9L,12L);assertThat(s.completed().applied()).containsExactlyInAnyOrder(5L,9L);});
        assertThat(runs.latestScan(from,to.minusDays(1))).isEmpty();
        // A monthly row from before V4 with the same range is not a scan and not a page of any generation.
        jdbc.sql("UPDATE collect_run SET strategy=NULL WHERE id IN (:ids)").param("ids",java.util.List.of(scan,page)).update();
        assertThat(runs.latestScan(from,to)).isEmpty();assertThat(runs.completedPage(scan,1)).isEmpty();
    }
    @Test void supplementalSessionLockExcludesAnotherOwnerAndReleasesAfterCloseAndBackendDeath() {
        var leases=new AdvisoryLease(dataSource);
        var firstLease=leases.acquire(AdvisoryLease.BACKFILL).orElseThrow();
        assertThat(leases.acquire(AdvisoryLease.BACKFILL)).isEmpty();
        firstLease.close();
        var interrupted=leases.acquire(AdvisoryLease.BACKFILL).orElseThrow();
        jdbc.sql("SELECT pg_terminate_backend(pid) FROM pg_locks WHERE locktype='advisory' AND objid=:key")
                .param("key",AdvisoryLease.BACKFILL).query(Boolean.class).list();
        assertThatThrownBy(interrupted::close).hasMessage("DATABASE_FAILURE");
        try(var resumed=leases.acquire(AdvisoryLease.BACKFILL).orElseThrow()) {
            assertThat(leases.acquire(AdvisoryLease.BACKFILL)).isEmpty();
        }
    }
    @Test void leaseClosesItsPhysicalConnectionWhenUnlockFailsOnALiveSessionSoAnotherOwnerAcquires() throws Exception {
        // Double: the session stays alive but the unlock statement fails (e.g. statement timeout or cancel).
        javax.sql.DataSource failingUnlock=new org.springframework.jdbc.datasource.DelegatingDataSource(dataSource) {
            @Override public java.sql.Connection getConnection() throws java.sql.SQLException {
                var real=super.getConnection();
                return (java.sql.Connection)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                        new Class<?>[]{java.sql.Connection.class},(proxy,method,args)->{
                            if(method.getName().equals("prepareStatement") && ((String)args[0]).contains("unlock"))
                                throw new java.sql.SQLException("canceling statement due to statement timeout","57014");
                            try {return method.invoke(real,args);} catch(java.lang.reflect.InvocationTargetException e) {throw e.getCause();}
                        });
            }
        };
        var lease=new AdvisoryLease(failingUnlock).acquire(AdvisoryLease.DETAILS).orElseThrow();
        int owner=jdbc.sql("SELECT pid FROM pg_locks WHERE locktype='advisory' AND objid=:key AND granted")
                .param("key",AdvisoryLease.DETAILS).query(Integer.class).single();
        var others=new AdvisoryLease(dataSource);
        assertThat(others.acquire(AdvisoryLease.DETAILS)).isEmpty();
        assertThatThrownBy(lease::close).hasMessage("DATABASE_FAILURE");
        // The owner's backend ends with the closed connection; PostgreSQL then drops its lock.
        for(int i=0;i<50 && jdbc.sql("SELECT count(*) FROM pg_stat_activity WHERE pid=:pid").param("pid",owner).query(Integer.class).single()>0;i++)
            Thread.sleep(100);
        assertThat(jdbc.sql("SELECT count(*) FROM pg_stat_activity WHERE pid=:pid").param("pid",owner).query(Integer.class).single()).isZero();
        try(var next=others.acquire(AdvisoryLease.DETAILS).orElseThrow()) {
            assertThat(others.acquire(AdvisoryLease.DETAILS)).isEmpty();
        }
    }
    @Test void nonRegularKindsStopShortOfTheRegularDailyShare() {
        jdbc.sql("INSERT INTO api_budget(day,count) VALUES ('2026-11-16',827)").update();
        first.reserve("DETAIL");
        assertThatThrownBy(()->first.reserve("DETAIL")).hasMessage("BUDGET_EXHAUSTED");
        assertThatThrownBy(()->second.reserve("BACKFILL",2)).hasMessage("BUDGET_EXHAUSTED");
        for(int i=0;i<ApiBudget.REGULAR_SHARE;i++) first.reserve("REGULAR");
        assertThat(jdbc.sql("SELECT count FROM api_budget").query(Integer.class).single()).isEqualTo(900);
        assertThatThrownBy(()->second.reserve("REGULAR")).hasMessage("BUDGET_EXHAUSTED");
        assertThat(jdbc.sql("SELECT count(*) FROM api_attempt").query(Integer.class).single()).isEqualTo(1+ApiBudget.REGULAR_SHARE);
    }
    void postings(int count) {
        jdbc.sql("TRUNCATE institution CASCADE").update();
        jdbc.sql("INSERT INTO institution(code,name) VALUES ('TEST','fixture')").update();
        jdbc.sql("""
                INSERT INTO posting(sn,inst_code,title,bgng_date,end_date,status,from_backfill,content_hash,first_seen_at,last_seen_at)
                SELECT sn,'TEST','fixture','2026-11-01','2026-12-01','OPEN',false,'fixture',now(),now() FROM generate_series(1,:n) sn
                """).param("n",count).update();
    }
    static final String INVALID_DETAIL="{\"resultCode\":200,\"result\":{\"recrutPblntSn\":42}}";
    @Test void detailRetriesStopAfterThreeFailedRunsAndTheGapStaysVisible() {
        posting(); var client=org.mockito.Mockito.mock(RecruitmentClient.class);
        org.mockito.Mockito.when(client.detail(42)).thenThrow(new CollectionFailure(CollectionFailure.Code.BUDGET_EXHAUSTED))
                .thenReturn(INVALID_DETAIL);
        var collector=new DetailCollector(jdbc,client,new CollectionRuns(jdbc,clock,manager),new AdvisoryLease(dataSource),clock);
        for(int slot=1;slot<=6;slot++) collector.collect("slot"+slot);
        // The budget stop is not the posting's failure; then three INVALID_DETAIL runs, then no more requests.
        org.mockito.Mockito.verify(client,org.mockito.Mockito.times(4)).detail(42);
        assertThat(jdbc.sql("SELECT detail_status FROM posting WHERE sn=42").query(String.class).single()).isEqualTo("FAILED");
        var registry=new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        try {
            new CollectorMetrics(registry,jdbc,clock,new CollectorProperties(false,null,500,900,false,null,null,null,false));
            assertThat(registry.get("collector.detail.gave.up").gauge().value()).isEqualTo(1);
        } finally {registry.close();}
    }
    @Test void oneSweepStopsAtThirtyPostingsOrTenMinutes() {
        postings(40); var client=org.mockito.Mockito.mock(RecruitmentClient.class);
        org.mockito.Mockito.when(client.detail(org.mockito.ArgumentMatchers.anyLong())).thenReturn(INVALID_DETAIL);
        var runs=new CollectionRuns(jdbc,clock,manager);
        new DetailCollector(jdbc,client,runs,new AdvisoryLease(dataSource),clock).collect("count");
        org.mockito.Mockito.verify(client,org.mockito.Mockito.times(DetailCollector.MAX_PER_SWEEP)).detail(org.mockito.ArgumentMatchers.anyLong());
        org.mockito.Mockito.clearInvocations(client);
        // Each clock read moves four minutes: 4 and 8 fit before the 10-minute deadline, 12 does not.
        var start=clock.instant();var reads=new java.util.concurrent.atomic.AtomicInteger();
        var ticking=new Clock() {
            @Override public java.time.ZoneId getZone() {return ZoneOffset.UTC;}
            @Override public Clock withZone(java.time.ZoneId zone) {return this;}
            @Override public Instant instant() {return start.plus(java.time.Duration.ofMinutes(4L*reads.getAndIncrement()));}
        };
        new DetailCollector(jdbc,client,runs,new AdvisoryLease(dataSource),ticking).collect("time");
        org.mockito.Mockito.verify(client,org.mockito.Mockito.times(2)).detail(org.mockito.ArgumentMatchers.anyLong());
    }
    @Test void alwaysInvalidDetailsAcrossManySlotsLeaveTheRegularShare() throws Exception {
        postings(40);
        var budget=new ApiBudget(jdbc,manager,clock,new CollectorProperties(false,null,50,100,false,null,null,null,false));
        var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        var requests=new java.util.concurrent.atomic.AtomicInteger();
        server.createContext("/",e->{requests.incrementAndGet();byte[] bytes=INVALID_DETAIL.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            e.sendResponseHeaders(200,bytes.length);e.getResponseBody().write(bytes);e.close();});server.start();
        try(var http=java.net.http.HttpClient.newHttpClient()) {
            var client=new RecruitmentClient(http,budget,"fixture",java.net.URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/"),
                    d->{},clock,java.time.Duration.ofSeconds(2));
            var collector=new DetailCollector(jdbc,client,new CollectionRuns(jdbc,clock,manager),new AdvisoryLease(dataSource),clock);
            for(int slot=1;slot<=24;slot++) collector.collect("slot"+slot);
        } finally {server.stop(0);}
        int share=ApiBudget.REGULAR_SHARE;
        assertThat(requests).hasValue(100-share);
        assertThat(jdbc.sql("SELECT count FROM api_budget").query(Integer.class).single()).isEqualTo(100-share);
        for(int i=0;i<share;i++) budget.reserve("REGULAR");
        assertThatThrownBy(()->budget.reserve("REGULAR")).hasMessage("BUDGET_EXHAUSTED");
    }
    @Test void unexpectedDetailErrorsMarkThePostingFailedAndStopAtTheCap() {
        posting(); var client=org.mockito.Mockito.mock(RecruitmentClient.class);
        org.mockito.Mockito.when(client.detail(42)).thenThrow(new IllegalStateException("unexpected"));
        var collector=new DetailCollector(jdbc,client,new CollectionRuns(jdbc,clock,manager),new AdvisoryLease(dataSource),clock);
        collector.collect("slot1");
        assertThat(jdbc.sql("SELECT detail_status FROM posting WHERE sn=42").query(String.class).single()).isEqualTo("FAILED");
        for(int slot=2;slot<=5;slot++) collector.collect("slot"+slot);
        org.mockito.Mockito.verify(client,org.mockito.Mockito.times(DetailCollector.MAX_FAILURES)).detail(42);
        assertThat(jdbc.sql("SELECT error FROM collect_run WHERE kind='DETAIL' ORDER BY id").query(String.class).list())
                .containsExactly("INGESTION_FAILED","INGESTION_FAILED","INGESTION_FAILED");
        assertThat(jdbc.sql("SELECT detail_status FROM posting WHERE sn=42").query(String.class).single()).isEqualTo("FAILED");
    }
    @Test void officialDetailSampleIsStoredWithoutWrappers() throws Exception {
        jdbc.sql("TRUNCATE institution CASCADE").update();
        jdbc.sql("INSERT INTO institution(code,name) VALUES ('C0008','fixture')").update();
        jdbc.sql("""
                INSERT INTO posting(sn,inst_code,title,bgng_date,end_date,status,from_backfill,content_hash,first_seen_at,last_seen_at)
                VALUES (307032,'C0008','fixture','2026-11-13','2026-11-20','OPEN',false,'fixture',now(),now())
                """).update();
        var collector=new DetailCollector(jdbc,org.mockito.Mockito.mock(RecruitmentClient.class),
                new CollectionRuns(jdbc,clock,manager),new AdvisoryLease(dataSource),clock);
        collector.save(307032,java.nio.file.Files.readString(java.nio.file.Path.of("fixtures/alio-official-detail-sample.json")));
        assertThat(jdbc.sql("""
                SELECT detail_status||'|'||jsonb_array_length(steps)||'|'||jsonb_array_length(files)||'|'||(steps->0->>'recrutStepSn' IS NOT NULL)
                  ||'|'||(files->0->>'atchFileNm' IS NOT NULL) FROM posting WHERE sn=307032""").query(String.class).single())
                .isEqualTo("DONE|4|3|true|true");
    }
    void posting() {
        jdbc.sql("TRUNCATE institution CASCADE").update();
        jdbc.sql("INSERT INTO institution(code,name) VALUES ('TEST','fixture')").update();
        jdbc.sql("""
                INSERT INTO posting(sn,inst_code,title,bgng_date,end_date,status,from_backfill,content_hash,first_seen_at,last_seen_at)
                VALUES (42,'TEST','fixture','2026-11-01','2026-12-01','OPEN',false,'fixture',now(),now())
                """).update();
    }
    @Test void detailIsIndependentAndMalformedResponseKeepsSavedArrays() {
        posting();
        var collector=new DetailCollector(jdbc,org.mockito.Mockito.mock(RecruitmentClient.class),
                new CollectionRuns(jdbc,clock,manager),new AdvisoryLease(dataSource),clock);
        collector.save(42,"{\"resultCode\":\"200\",\"result\":{\"recrutPblntSn\":\"42\",\"steps\":[{\"step\":{\"recrutPbancTtl\":\"stage\"}}],\"files\":[{\"file\":{\"atchFileNm\":\"fixture.pdf\"}}]}}");
        String before=jdbc.sql("SELECT steps::text || files::text FROM posting WHERE sn=42").query(String.class).single();
        assertThatThrownBy(()->collector.save(42,"{\"resultCode\":200,\"result\":{\"recrutPblntSn\":42}}"))
                .hasMessage("INVALID_DETAIL");
        assertThat(jdbc.sql("SELECT detail_status FROM posting WHERE sn=42").query(String.class).single()).isEqualTo("DONE");
        assertThat(jdbc.sql("SELECT steps::text || files::text FROM posting WHERE sn=42").query(String.class).single()).isEqualTo(before);
        assertThat(jdbc.sql("SELECT title FROM posting WHERE sn=42").query(String.class).single()).isEqualTo("fixture");
    }
    @Test void detailsRetryFailuresAtNextSlotButNeverRequestSuccessfulOrHistoricalPostingAgain() {
        posting(); var client=org.mockito.Mockito.mock(RecruitmentClient.class);
        org.mockito.Mockito.when(client.detail(42)).thenThrow(new CollectionFailure(CollectionFailure.Code.HTTP_TIMEOUT))
                .thenReturn("{\"resultCode\":200,\"result\":{\"recrutPblntSn\":42,\"steps\":[],\"files\":[]}}");
        var collector=new DetailCollector(jdbc,client,new CollectionRuns(jdbc,clock,manager),new AdvisoryLease(dataSource),clock);
        collector.collect("slot1");
        assertThat(jdbc.sql("SELECT detail_status FROM posting WHERE sn=42").query(String.class).single()).isEqualTo("FAILED");
        collector.collect("slot1"); org.mockito.Mockito.verify(client).detail(42);
        collector.collect("slot2"); collector.collect("slot3");
        org.mockito.Mockito.verify(client,org.mockito.Mockito.times(2)).detail(42);
        jdbc.sql("UPDATE posting SET from_backfill=true, detail_status='NONE'").update();
        collector.collect("slot4"); org.mockito.Mockito.verifyNoMoreInteractions(client);
    }
    @Test void everyAttemptSurvivesCallerRollbackAndServiceRestartInKstDay() {
        var tx=new TransactionTemplate(manager);
        assertThatThrownBy(()->tx.executeWithoutResult(status->{ first.reserve("REGULAR"); throw new IllegalStateException(); }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.sql("SELECT count FROM api_budget WHERE day='2026-11-16'").query(Integer.class).single()).isEqualTo(1);
        long next=second.reserve("DETAIL"); second.finish(next,"HTTP_TIMEOUT");
        assertThat(jdbc.sql("SELECT count(*) FROM api_attempt").query(Integer.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT count(*) FROM api_attempt WHERE outcome IS NULL").query(Integer.class).single()).isEqualTo(1);
    }
    @Test void twoInstancesRacingAt899MakeExactlyOneReservation() throws Exception {
        jdbc.sql("INSERT INTO api_budget(day,count) VALUES ('2026-11-16',899)").update();
        var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->reserve(start,first)); var b=pool.submit(()->reserve(start,second)); start.countDown();
            assertThat(a.get()+b.get()).isEqualTo(1);
        }
        assertThat(jdbc.sql("SELECT count FROM api_budget").query(Integer.class).single()).isEqualTo(900);
        assertThat(jdbc.sql("SELECT count(*) FROM api_attempt").query(Integer.class).single()).isEqualTo(1);
    }
    int reserve(CountDownLatch start,ApiBudget budget) throws Exception {
        start.await(); try {budget.reserve("REGULAR");return 1;}
        catch(CollectionFailure e) { assertThat(e.code()).isEqualTo(CollectionFailure.Code.BUDGET_EXHAUSTED); return 0; }
    }
    @Test void metricsExposePersistedAttemptsRetriesAndLastCompleteAfterRestart() {
        first.reserve("REGULAR",1);first.reserve("REGULAR",2);
        jdbc.sql("""
                INSERT INTO collect_run(kind,slot,started_at,finished_at,result) VALUES
                  ('REGULAR','one',now(), '2026-11-14T00:00:00Z','COMPLETE'),
                  ('REGULAR','two',now(),now(),'FAILED'),('REGULAR','three',now(),now(),'INCOMPLETE')
                """).update();
        var registry=new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        try {
            new CollectorMetrics(registry,jdbc,clock,new CollectorProperties(false,null,500,900,false,null,null,null,false));
            assertThat(registry.get("collector.api.attempts.today").gauge().value()).isEqualTo(2);
            assertThat(registry.get("collector.api.retries.today").gauge().value()).isEqualTo(1);
            assertThat(registry.get("collector.regular.incomplete.consecutive").gauge().value()).isEqualTo(2);
            assertThat(registry.get("collector.last.complete.epoch").gauge().value())
                    .isEqualTo(Instant.parse("2026-11-14T00:00:00Z").getEpochSecond());
        } finally {registry.close();}
    }
    @Test void warningThresholdIsCrossedOnceAndNextDayHasFreshBudget() {
        jdbc.sql("INSERT INTO api_budget(day,count) VALUES ('2026-11-16',499)").update();
        var logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(ApiBudget.class);
        var events=new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();events.start();logger.addAppender(events);
        try {first.reserve("BACKFILL");second.reserve("BACKFILL");}
        finally {logger.detachAppender(events);events.stop();}
        assertThat(events.list.stream().filter(e->e.getFormattedMessage().equals("API_BUDGET_WARNING")).count()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count FROM api_budget").query(Integer.class).single()).isEqualTo(501);
        var tomorrow=new ApiBudget(jdbc,manager,Clock.fixed(Instant.parse("2026-11-16T15:00:00Z"),ZoneOffset.UTC),
                new CollectorProperties(false,null,500,900,false,null,null,null,false));
        tomorrow.reserve("REGULAR");
        assertThat(jdbc.sql("SELECT count FROM api_budget WHERE day='2026-11-17'").query(Integer.class).single()).isEqualTo(1);
    }
    @Test void twoRunOwnersClaimOneSlotAndCompletedResultCannotBeOverwritten() throws Exception {
        var runs=new CollectionRuns(jdbc,clock,manager); var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->{start.await();return runs.claim("REGULAR","2026-11-16T00:00+09:00");});
            var b=pool.submit(()->{start.await();return runs.claim("REGULAR","2026-11-16T00:00+09:00");}); start.countDown();
            assertThat((a.get().isPresent()?1:0)+(b.get().isPresent()?1:0)).isEqualTo(1);
        }
        long id=jdbc.sql("SELECT id FROM collect_run").query(Long.class).single();
        runs.finish(id,"COMPLETE",null,1,1,1); runs.fail(id,CollectionFailure.Code.HTTP_TIMEOUT);
        assertThat(jdbc.sql("SELECT result FROM collect_run").query(String.class).single()).isEqualTo("COMPLETE");
    }
}
