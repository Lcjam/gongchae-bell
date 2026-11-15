package kr.gongchae.bell.ingestion.runtime;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import kr.gongchae.bell.ingestion.IngestionException;
import kr.gongchae.bell.ingestion.PostingIngestor;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Initial load as one START_SCAN: list?pbancBgngYmd=from read to the end, rows starting after `to` not applied. */
@Testcontainers
class BackfillRuntimeTest {
    @Container static PostgreSQLContainer postgres=new PostgreSQLContainer("postgres:17");
    final Clock clock=Clock.fixed(Instant.parse("2026-11-15T15:01:00Z"),ApiBudget.KST);
    final JsonMapper json=JsonMapper.builder().build();
    JdbcClient jdbc;
    JdbcTransactionManager manager;
    AdvisoryLease leases;
    CollectionRuns runs;
    RecruitmentClient client;
    PostingIngestor ingestor;
    ObjectNode source;
    @BeforeEach void setup() throws Exception {
        var ds=new DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());
        Flyway.configure().dataSource(ds).load().migrate();jdbc=JdbcClient.create(ds);manager=new JdbcTransactionManager(ds);
        jdbc.sql("TRUNCATE institution,collect_run,api_attempt,api_budget RESTART IDENTITY CASCADE").update();
        runs=new CollectionRuns(jdbc,clock,manager);leases=new AdvisoryLease(ds);client=mock(RecruitmentClient.class);
        ingestor=new PostingIngestor(jdbc,manager,clock);
        source=(ObjectNode)json.readTree(Files.readString(Path.of("fixtures/alio-public-list-sample.json"))).path("data").path("result").get(0);
    }
    String page(int total,int count,int firstSn,String begin) {
        var root=json.createObjectNode();root.put("resultCode",200).put("totalCount",total);var rows=root.putArray("result");
        for(int i=0;i<count;i++) rows.add(row(firstSn+i,begin,"20261231",source.path("recrutPbancTtl").asString()));
        return json.writeValueAsString(root);
    }
    String page(int total,ObjectNode... rows) {
        var root=json.createObjectNode();root.put("resultCode",200).put("totalCount",total);root.putArray("result").addAll(java.util.List.of(rows));
        return json.writeValueAsString(root);
    }
    ObjectNode row(long sn,String begin,String title) {return row(sn,begin,"20261231",title);}
    ObjectNode row(long sn,String begin,String end,String title) {
        var row=source.deepCopy();row.put("recrutPblntSn",sn).put("pbancBgngYmd",begin).put("pbancEndYmd",end).put("recrutPbancTtl",title);
        return row;
    }
    BackfillCollector collector() {return new BackfillCollector(runs,client,ingestor,leases,clock);}
    /** total|received|unique|applied of one collect_run row ("null" when unset). */
    String totals(String slot) {
        return jdbc.sql("""
                SELECT coalesce(total_count::text,'null')||'|'||coalesce(received::text,'null')||'|'||coalesce(unique_count::text,'null')
                  ||'|'||coalesce(applied_count::text,'null') FROM collect_run WHERE slot=:slot
                """).param("slot",slot).query(String.class).single();
    }
    int count(String tableAndCondition) {return jdbc.sql("SELECT count(*) FROM "+tableAndCondition).query(Integer.class).single();}

    @Test void monthCrossingLateDeadlineAndBoundaryStartsAreSelectedByStartDateOnly() {
        var from=LocalDate.of(2026,10,1);var to=LocalDate.of(2026,11,15);
        // Stored by the regular run with a collected detail: the scan may update it but never its origin or detail.
        ingestor.upsertPage(ingestor.parsePage(page(1,row(7000,"20261020","정기 수집"))),clock.instant());
        jdbc.sql("UPDATE posting SET from_backfill=false, detail_status='DONE', steps='[{\"kept\":1}]'::jsonb WHERE sn=7000").update();
        when(client.backfillList(from,1)).thenReturn(page(6,
                row(1,"20261001","20261010","시작일이 from"),row(2,"20261025","20261105","월 걸침"),
                row(3,"20261110","20261231","목표 종료일 뒤 마감"),row(4,"20261115","20261120","시작일이 to"),
                row(5,"20261116","20261120","미래 시작"),row(7000,"20261020","정기 수집")));
        assertThat(collector().collect("select",new BackfillRange(from,to),false)).isEqualTo(BackfillCollector.Outcome.COMPLETE);
        verify(client).backfillList(from,1);verifyNoMoreInteractions(client);
        assertThat(jdbc.sql("SELECT sn FROM posting WHERE sn<100 ORDER BY sn").query(Long.class).list()).containsExactly(1L,2L,3L,4L);
        assertThat(jdbc.sql("SELECT status FROM posting WHERE sn=3").query(String.class).single()).isEqualTo("OPEN");
        assertThat(jdbc.sql("SELECT from_backfill||'|'||detail_status||'|'||steps::text FROM posting WHERE sn=7000").query(String.class).single())
                .isEqualTo("false|DONE|[{\"kept\": 1}]");
        // Raw counts every received row; applied counts the five that started within [from, to].
        assertThat(totals("select/scan")).isEqualTo("6|6|6|5");
        assertThat(totals("command/select")).isEqualTo("6|6|6|5");
        assertThat(jdbc.sql("SELECT applied_sns::text FROM collect_run WHERE slot='select/scan'").query(String.class).single())
                .isEqualTo("[1, 2, 3, 4, 7000]");
        assertThat(jdbc.sql("SELECT received_sns::text FROM collect_run WHERE slot='select/scan/1'").query(String.class).single())
                .isEqualTo("[1, 2, 3, 4, 5, 7000]");
    }
    @Test void rawRowsOutsideTheTargetAreReadToTheEndButNotApplied() {
        var from=LocalDate.of(2026,11,1);var to=LocalDate.of(2026,11,15);
        when(client.backfillList(from,1)).thenReturn(page(1001,1000,100,"20261116"));
        when(client.backfillList(from,2)).thenReturn(page(1001,row(5000,"20261115","목표 안")));
        assertThat(collector().collect("tail",new BackfillRange(from,to),false)).isEqualTo(BackfillCollector.Outcome.COMPLETE);
        assertThat(totals("tail/scan/1")).as("no target row on page 1, still checkpointed").isEqualTo("1001|1000|1000|0");
        assertThat(totals("tail/scan")).isEqualTo("1001|1001|1001|1");
        assertThat(jdbc.sql("SELECT sn FROM posting").query(Long.class).list()).containsExactly(5000L);
    }
    @Test void officialLowerBoundPageKeepsItsRealShapeThroughSelection() throws Exception {
        var from=LocalDate.of(2023,11,15);var to=LocalDate.of(2026,11,15);
        String body=Files.readString(Path.of("fixtures/alio-official-list-lower-bound-sample.json"));
        var parsed=ingestor.parsePage(body);
        assertThat(parsed.totalCount()).isEqualTo(36772);assertThat(parsed.sns()).hasSize(1000);
        assertThat(parsed.beginsWithin(from,LocalDate.MAX)).isTrue();
        assertThat(parsed.startingOnOrBefore(to).sns()).hasSize(996).doesNotContain(306937L,306938L,306939L,306940L)
                .contains(306986L,307032L);
        // The real page as a whole scan: only totalCount is rewritten (36,772 -> 1,000); every row is as received.
        var single=(ObjectNode)json.readTree(body);single.put("totalCount",1000);
        when(client.backfillList(from,1)).thenReturn(json.writeValueAsString(single));
        assertThat(collector().collect("official",new BackfillRange(from,to),false)).isEqualTo(BackfillCollector.Outcome.COMPLETE);
        assertThat(totals("official/scan")).isEqualTo("1000|1000|1000|996");
        assertThat(count("posting")).isEqualTo(996);
        assertThat(count("posting WHERE date_trunc('month',bgng_date)<>date_trunc('month',end_date)")).isEqualTo(374);
        assertThat(count("posting WHERE end_date>DATE '2026-11-15'")).isEqualTo(377);
        assertThat(count("posting WHERE bgng_date>DATE '2026-11-15'")).isZero();
    }
    @Test void legacyMonthlyRowsForTheSamePeriodAreNeverReused() {
        var from=LocalDate.of(2026,11,1);var to=LocalDate.of(2026,11,15);var period=new BackfillRange(from,to);
        // Rows of the monthly method before V4 (strategy NULL); the month row's range equals the new period exactly.
        jdbc.sql("""
                INSERT INTO collect_run(kind,slot,started_at,finished_at,result,total_count,received,unique_count,
                  period_from,period_to,range_from,range_to,page_no,received_sns) VALUES
                ('BACKFILL','command/initial-001',now(),now(),'COMPLETE',1,1,1,'2023-11-15','2026-11-15',NULL,NULL,NULL,NULL),
                ('BACKFILL','initial-001/2026-11-01',now(),now(),'COMPLETE',1,1,1,'2023-11-15','2026-11-15','2026-11-01','2026-11-15',NULL,'[100]'),
                ('BACKFILL','initial-001/2026-11-01/1',now(),now(),'COMPLETE',1,1,1,'2023-11-15','2026-11-15','2026-11-01','2026-11-15',1,'[100]')
                """).update();
        String legacy="SELECT string_agg(slot||'|'||result||'|'||coalesce(received_sns::text,'null')||'|'||coalesce(strategy,'legacy'),',' ORDER BY id) FROM collect_run WHERE slot LIKE '%initial-001%'";
        String before=jdbc.sql(legacy).query(String.class).single();
        when(client.backfillList(from,1)).thenReturn(page(2,row(100,"20261101","월 안"),row(200,"20261110","20261205","월 걸침")));
        assertThat(collector().collect("start-scan",period,false)).isEqualTo(BackfillCollector.Outcome.COMPLETE);
        assertThat(totals("start-scan/scan")).isEqualTo("2|2|2|2");
        assertThat(count("posting")).isEqualTo(2);
        assertThat(jdbc.sql(legacy).query(String.class).single()).isEqualTo(before);
        // A completed START_SCAN is reused by a new slot without HTTP; an explicit restart reads again.
        assertThat(collector().collect("replay",period,false)).isEqualTo(BackfillCollector.Outcome.COMPLETE);
        assertThat(totals("command/replay")).isEqualTo("2|2|2|2");
        assertThat(count("collect_run WHERE slot LIKE 'replay/%'")).isZero();
        assertThat(collector().collect("rescan",period,true)).isEqualTo(BackfillCollector.Outcome.COMPLETE);
        verify(client,times(2)).backfillList(from,1);verifyNoMoreInteractions(client);
        assertThat(jdbc.sql("SELECT scan_generation=id FROM collect_run WHERE slot='rescan/scan'").query(Boolean.class).single()).isTrue();
    }
    @Test void explicitRestartStartsANewGenerationAndPagesWithTheSameTotalAreNeverMixed() {
        var from=LocalDate.of(2026,11,1);var period=new BackfillRange(from,LocalDate.of(2026,11,30));
        // Generation A: pages 1-2 complete, page 3 fails. Generation B (restart): page 1 complete, page 2 fails. Same total.
        when(client.backfillList(from,1)).thenReturn(page(3000,1000,10000,"20261101")).thenReturn(page(3000,1000,20000,"20261101"));
        when(client.backfillList(from,2)).thenReturn(page(3000,1000,11000,"20261101"))
                .thenThrow(new CollectionFailure(CollectionFailure.Code.HTTP_STATUS)).thenReturn(page(3000,1000,21000,"20261101"));
        when(client.backfillList(from,3)).thenThrow(new CollectionFailure(CollectionFailure.Code.HTTP_STATUS))
                .thenReturn(page(3000,1000,22000,"20261101"));
        assertThatThrownBy(()->collector().collect("gen-a",period,false)).hasMessage("HTTP_STATUS");
        assertThatThrownBy(()->collector().collect("gen-b",period,true)).hasMessage("HTTP_STATUS");
        // Continues generation B; taking each page number's latest COMPLETE row would have used A's page 2.
        assertThat(collector().collect("gen-b-resume",period,false)).isEqualTo(BackfillCollector.Outcome.COMPLETE);
        assertThat(totals("gen-b-resume/scan")).isEqualTo("3000|3000|3000|3000");
        assertThat(jdbc.sql("""
                SELECT count(*) FROM jsonb_array_elements_text((SELECT received_sns FROM collect_run WHERE slot='gen-b-resume/scan')) sn
                WHERE sn::bigint < 20000
                """).query(Integer.class).single()).isZero();
        var generation="SELECT scan_generation FROM collect_run WHERE slot=:slot";
        long a=jdbc.sql(generation).param("slot","gen-a/scan").query(Long.class).single();
        long b=jdbc.sql(generation).param("slot","gen-b/scan").query(Long.class).single();
        assertThat(b).isNotEqualTo(a);
        assertThat(jdbc.sql(generation).param("slot","gen-b-resume/scan").query(Long.class).single()).isEqualTo(b);
        assertThat(count("collect_run WHERE slot='gen-b-resume/scan/1'")).as("page 1 reused from B").isZero();
        verify(client,times(2)).backfillList(from,1);verify(client,times(3)).backfillList(from,2);verify(client,times(2)).backfillList(from,3);
        verifyNoMoreInteractions(client);
        assertThat(count("posting")).as("rows applied by both generations stay stored").isEqualTo(5000);
    }
    @Test void completedScanIsReusedOnlyThroughItsOwnSetsAfterUpstreamShrankAndARestart() {
        var from=LocalDate.of(2026,11,1);var period=new BackfillRange(from,LocalDate.of(2026,11,30));
        when(client.backfillList(from,1)).thenReturn(page(2001,1000,100,"20261101")).thenReturn(page(1,1,100,"20261101"));
        when(client.backfillList(from,2)).thenReturn(page(2001,1000,1100,"20261101"));
        when(client.backfillList(from,3)).thenReturn(page(2001,1,2100,"20261131"));
        assertThatThrownBy(()->collector().collect("shrink-1",period,false)).isInstanceOf(IngestionException.class);
        // Upstream now reports one posting: the operator starts a new generation.
        assertThat(collector().collect("shrink-2",period,true)).isEqualTo(BackfillCollector.Outcome.COMPLETE);
        // Pages 1 and 2 of the first generation stay COMPLETE; a re-union of them would give 2000 SNs against unique=1.
        assertThat(collector().collect("shrink-3",period,false)).isEqualTo(BackfillCollector.Outcome.COMPLETE);
        assertThat(jdbc.sql("SELECT received_sns::text FROM collect_run WHERE slot='shrink-2/scan'").query(String.class).single()).isEqualTo("[100]");
        assertThat(totals("command/shrink-3")).isEqualTo("1|1|1|1");
        verify(client,times(2)).backfillList(from,1);verify(client).backfillList(from,2);verify(client).backfillList(from,3);
        verifyNoMoreInteractions(client);
    }
    @Test void rejectedPagesCommitNoChangeToStoredPostings() {
        var from=LocalDate.of(2026,11,1);var period=new BackfillRange(from,LocalDate.of(2026,11,30));
        record Case(String name,String message,String result,Runnable stub,String scan) {}
        var cases=java.util.List.of(
                new Case("lower bound","BACKFILL_RANGE_MISMATCH","FAILED",()->when(client.backfillList(from,1))
                        .thenReturn(page(2,row(100,"20261101","바뀐 제목"),row(5000,"20261031","기간 밖"))),"null|0|0|0"),
                new Case("changed total","BACKFILL_COUNT_CHANGED","FAILED",()->{when(client.backfillList(from,1)).thenReturn(page(1001,1000,100,"20261101"));
                        when(client.backfillList(from,2)).thenReturn(page(1002,row(100,"20261101","바뀐 제목"),row(5000,"20261101","새 공고")));},
                        "1001|1000|1000|1000"),
                // sn 50 is new and checked before the duplicate 100: it must not reach the scan's SN sets.
                new Case("duplicate","BACKFILL_DUPLICATE_SN","FAILED",()->{when(client.backfillList(from,1)).thenReturn(page(1002,1000,100,"20261101"));
                        when(client.backfillList(from,2)).thenReturn(page(1002,row(50,"20261101","새 공고"),row(100,"20261101","바뀐 제목")));},
                        "1002|1000|1000|1000"),
                // An out-of-target row is still checked: its duplicate rejects the page with a new in-target row.
                new Case("duplicate outside target","BACKFILL_DUPLICATE_SN","FAILED",()->{
                        var first=(ObjectNode)json.readTree(page(1002,999,100,"20261101"));
                        ((tools.jackson.databind.node.ArrayNode)first.get("result")).add(row(5000,"20261201","목표 밖"));
                        when(client.backfillList(from,1)).thenReturn(json.writeValueAsString(first));
                        when(client.backfillList(from,2)).thenReturn(page(1002,row(50,"20261101","새 공고"),row(5000,"20261201","목표 밖")));},
                        "1002|1000|1000|999"),
                new Case("short page","BACKFILL_PAGE_MISMATCH","FAILED",()->when(client.backfillList(from,1))
                        .thenReturn(page(3,row(100,"20261101","바뀐 제목"),row(5000,"20261101","새 공고"))),"null|0|0|0"),
                // A malformed row that would not be applied (it starts after `to`) still rejects the whole page.
                new Case("malformed outside target","INVALID_ROW","INCOMPLETE",()->when(client.backfillList(from,1))
                        .thenReturn(page(2,row(100,"20261101","바뀐 제목"),row(5000,"20261201","2026-12-31","목표 밖")))
                        ,"null|0|0|0"));
        for(var c:cases) {
            jdbc.sql("TRUNCATE institution,collect_run RESTART IDENTITY CASCADE").update();
            ingestor.upsertPage(ingestor.parsePage(page(1,1,100,"20261101")),clock.instant());
            reset(client);c.stub().run();
            var stored="SELECT sn::text||'|'||title||'|'||content_hash FROM posting WHERE sn=100";
            String before=jdbc.sql(stored).query(String.class).single();
            assertThatThrownBy(()->collector().collect("rejected",period,false)).as(c.name()).hasMessageStartingWith(c.message());
            assertThat(jdbc.sql(stored).query(String.class).single()).as(c.name()).isEqualTo(before);
            assertThat(count("posting WHERE sn IN (50,5000)")).as(c.name()).isZero();
            assertThat(count("posting_change")).as(c.name()).isZero();
            assertThat(jdbc.sql("SELECT result FROM collect_run WHERE slot='rejected/scan'").query(String.class).single()).as(c.name()).isEqualTo(c.result());
            assertThat(totals("rejected/scan")).as(c.name()).isEqualTo(c.scan());
            assertThat(totals("command/rejected")).as(c.name()).isEqualTo(c.scan());
        }
    }
    @Test void failedScanKeepsAcceptedPageTotalsOnItsCommandAndScanRows() {
        var from=LocalDate.of(2026,10,1);var period=new BackfillRange(from,LocalDate.of(2026,11,30));
        when(client.backfillList(from,1)).thenReturn(page(1001,1000,100,"20261001"));
        when(client.backfillList(from,2)).thenThrow(new CollectionFailure(CollectionFailure.Code.HTTP_STATUS))
                .thenThrow(new IllegalStateException("unexpected"));
        assertThatThrownBy(()->collector().collect("http",period,false)).hasMessage("HTTP_STATUS");
        assertThatThrownBy(()->collector().collect("crash",period,false)).hasMessage("INGESTION_FAILED");
        var row="SELECT result||'|'||error FROM collect_run WHERE slot=:slot";
        java.util.function.Function<String,String> result=slot->jdbc.sql(row).param("slot",slot).query(String.class).single();
        assertThat(result.apply("command/http")+"|"+totals("command/http")).isEqualTo("FAILED|HTTP_STATUS|1001|1000|1000|1000");
        assertThat(result.apply("http/scan")+"|"+totals("http/scan")).isEqualTo("FAILED|HTTP_STATUS|1001|1000|1000|1000");
        assertThat(result.apply("http/scan/2")+"|"+totals("http/scan/2")).isEqualTo("FAILED|HTTP_STATUS|null|null|null|null");
        assertThat(result.apply("command/crash")+"|"+totals("command/crash")).as("page 1 reused from the same generation")
                .isEqualTo("FAILED|INGESTION_FAILED|1001|1000|1000|1000");
        assertThat(jdbc.sql("SELECT result FROM collect_run WHERE slot='http/scan/1'").query(String.class).single()).isEqualTo("COMPLETE");
        verify(client).backfillList(from,1);verify(client,times(2)).backfillList(from,2);verifyNoMoreInteractions(client);
    }
    @Test void officialEmptyResponseCompletesAScanWithZeroPostings() throws Exception {
        var from=LocalDate.of(2010,1,1);var to=LocalDate.of(2010,1,31);
        // Real empty body (taken with a monthly request); here it stands for a scan whose upstream total is 0.
        when(client.backfillList(from,1)).thenReturn(Files.readString(Path.of("fixtures/alio-official-list-empty-sample.json")));
        assertThat(collector().collect("official-empty",new BackfillRange(from,to),false)).isEqualTo(BackfillCollector.Outcome.COMPLETE);
        assertThat(jdbc.sql("SELECT total_count||'|'||received||'|'||unique_count||'|'||received_sns::text||'|'||applied_count||'|'||applied_sns::text FROM collect_run WHERE slot='official-empty/scan'")
                .query(String.class).single()).isEqualTo("0|0|0|[]|0|[]");
    }
    @Test void resumesAFailedPageInTheSameGenerationAndKeepsExactUniqueIds() {
        var from=LocalDate.of(2026,10,1);var period=new BackfillRange(from,LocalDate.of(2026,11,30));
        when(client.backfillList(from,1)).thenReturn(page(1001,1000,100,"20261101"));
        when(client.backfillList(from,2)).thenReturn(page(1001,1,1100,"20261131")).thenReturn(page(1001,1,1100,"20261001"));
        assertThatThrownBy(()->collector().collect("first",period,false)).isInstanceOf(IngestionException.class);
        assertThat(count("posting")).isEqualTo(1000);
        assertThat(jdbc.sql("SELECT result FROM collect_run WHERE slot='first/scan'").query(String.class).single()).isEqualTo("INCOMPLETE");
        assertThat(jdbc.sql("SELECT error FROM collect_run WHERE slot='first/scan/2'").query(String.class).single()).isEqualTo("INVALID_ROW");
        assertThat(collector().collect("resume",period,false)).isEqualTo(BackfillCollector.Outcome.COMPLETE);
        assertThat(totals("resume/scan")).isEqualTo("1001|1001|1001|1001");
        assertThat(totals("command/resume")).isEqualTo("1001|1001|1001|1001");
        assertThat(count("posting")).isEqualTo(1001);
        assertThat(collector().collect("resume",period,false)).isEqualTo(BackfillCollector.Outcome.ALREADY_CLAIMED);
        assertThat(collector().collect("all-skipped",period,false)).isEqualTo(BackfillCollector.Outcome.COMPLETE);
        verify(client).backfillList(from,1);verify(client,times(2)).backfillList(from,2);verifyNoMoreInteractions(client);
        assertThat(count("posting WHERE from_backfill=true AND detail_status='NONE'")).isEqualTo(1001);
    }
    @Test void rejectsDuplicateAcrossPagesAndChangedTotalWithoutCompletingTheScan() {
        var from=LocalDate.of(2026,11,1);var period=new BackfillRange(from,LocalDate.of(2026,11,30));
        when(client.backfillList(from,1)).thenReturn(page(1001,1000,100,"20261101"));
        when(client.backfillList(from,2)).thenReturn(page(1001,1,100,"20261101"))
                .thenReturn(page(1002,2,1100,"20261101"));
        assertThatThrownBy(()->collector().collect("duplicate",period,false)).hasMessage("BACKFILL_DUPLICATE_SN");
        assertThatThrownBy(()->collector().collect("changed",period,false)).hasMessage("BACKFILL_COUNT_CHANGED");
        assertThat(count("collect_run WHERE page_no IS NULL AND result='COMPLETE'")).isZero();
        assertThat(count("collect_run WHERE page_no=1 AND result='COMPLETE'")).isEqualTo(1);
    }
    @Test void resumesAfterProcessExitBeforeOrAfterPageCheckpointWithoutLosingOrDuplicatingPostings() {
        var from=LocalDate.of(2026,11,1);var period=new BackfillRange(from,LocalDate.of(2026,11,30));
        for(boolean checkpointCommitted:new boolean[]{false,true}) {
            jdbc.sql("TRUNCATE institution,collect_run RESTART IDENTITY CASCADE").update();
            reset(client);when(client.backfillList(from,1)).thenReturn(page(2,2,100,"20261101"));
            var interruptedRuns=new CollectionRuns(jdbc,clock,manager) {
                @Override public void completePage(long id,CollectionRuns.Page page,int inserted,int changed,int closed) {
                    if(checkpointCommitted) super.completePage(id,page,inserted,changed,closed);
                    throw new AssertionError("SIMULATED_PROCESS_EXIT");
                }
            };
            var interrupted=new BackfillCollector(interruptedRuns,client,ingestor,leases,clock);
            assertThatThrownBy(()->interrupted.collect("interrupted",period,false)).isInstanceOf(AssertionError.class);
            assertThat(count("posting")).isEqualTo(2);
            assertThat(collector().collect("resumed",period,false)).isEqualTo(BackfillCollector.Outcome.COMPLETE);
            assertThat(count("posting")).isEqualTo(2);
            assertThat(count("posting_change")).isZero();
            assertThat(totals("resumed/scan")).isEqualTo("2|2|2|2");
            verify(client,times(checkpointCommitted?1:2)).backfillList(from,1);
            assertThat(count("collect_run WHERE result IS NULL")).isGreaterThanOrEqualTo(2);
        }
    }
    @Test void zeroScanWorksAndTwoManualSlotsCannotRunTheSameBackfillTogether() throws Exception {
        var from=LocalDate.of(2026,11,1);var period=new BackfillRange(from,LocalDate.of(2026,11,30));
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        when(client.backfillList(from,1)).thenAnswer(invocation->{entered.countDown();release.await(10,java.util.concurrent.TimeUnit.SECONDS);return page(0,0,0,"20261101");});
        try(var pool=Executors.newFixedThreadPool(2)) {
            var first=pool.submit(()->collector().collect("one",period,false));assertThat(entered.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            try {assertThat(collector().collect("two",period,false)).isEqualTo(BackfillCollector.Outcome.ALREADY_RUNNING);}
            finally {release.countDown();}
            assertThat(first.get(30,java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(BackfillCollector.Outcome.COMPLETE);
        }
        verify(client).backfillList(from,1);verifyNoMoreInteractions(client);
        assertThat(totals("one/scan")).isEqualTo("0|0|0|0");
    }
    @Test void rowsStartingBeforeTheLowerBoundFailTheScanAndNoHistoricalDetailIsRequested() {
        var from=LocalDate.of(2026,11,1);
        when(client.backfillList(from,1)).thenReturn(page(1,1,100,"20261031"));
        assertThatThrownBy(()->collector().collect("range",new BackfillRange(from,LocalDate.of(2026,11,30)),false)).hasMessage("BACKFILL_RANGE_MISMATCH");
        verify(client).backfillList(from,1);verifyNoMoreInteractions(client);
    }
}
