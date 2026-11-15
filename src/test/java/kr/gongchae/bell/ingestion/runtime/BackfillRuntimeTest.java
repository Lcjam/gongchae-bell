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
        for(int i=0;i<count;i++) {
            var row=source.deepCopy();row.put("recrutPblntSn",firstSn+i).put("pbancBgngYmd",begin).put("pbancEndYmd","20261231");rows.add(row);
        }
        return json.writeValueAsString(root);
    }
    String page(int total,ObjectNode... rows) {
        var root=json.createObjectNode();root.put("resultCode",200).put("totalCount",total);root.putArray("result").addAll(java.util.List.of(rows));
        return json.writeValueAsString(root);
    }
    ObjectNode row(long sn,String begin,String title) {
        var row=source.deepCopy();row.put("recrutPblntSn",sn).put("pbancBgngYmd",begin).put("pbancEndYmd","20261231").put("recrutPbancTtl",title);
        return row;
    }
    BackfillCollector collector() {return new BackfillCollector(runs,client,ingestor,leases,clock);}
    @Test void completedMonthIsSkippedByItsOwnSnSnapshotAfterUpstreamShrankAndPagesRestarted() {
        var from=LocalDate.of(2026,11,1);var to=LocalDate.of(2026,11,30);var period=new BackfillRange(from,to);
        when(client.backfillList(from,to,1)).thenReturn(page(2001,1000,100,"20261101")).thenReturn(page(1,1,100,"20261101"));
        when(client.backfillList(from,to,2)).thenReturn(page(2001,1000,1100,"20261101"));
        when(client.backfillList(from,to,3)).thenReturn(page(2001,1,2100,"20261131"));
        assertThatThrownBy(()->collector().collect("shrink-1",period,false)).isInstanceOf(IngestionException.class);
        // Upstream now reports one posting for the month: the operator restarts the incomplete month's pages.
        assertThat(collector().collect("shrink-2",period,true)).isEqualTo(BackfillCollector.Outcome.COMPLETE);
        // Pages 1 and 2 of the first attempt stay COMPLETE; a re-union of them would give 2000 SNs against unique=1.
        assertThat(collector().collect("shrink-3",period,false)).isEqualTo(BackfillCollector.Outcome.COMPLETE);
        assertThat(jdbc.sql("SELECT received_sns::text FROM collect_run WHERE slot='shrink-2/2026-11-01'").query(String.class).single()).isEqualTo("[100]");
        assertThat(jdbc.sql("SELECT unique_count FROM collect_run WHERE slot='command/shrink-3'").query(Integer.class).single()).isEqualTo(1);
        verify(client,times(2)).backfillList(from,to,1);verify(client).backfillList(from,to,2);verify(client).backfillList(from,to,3);
        verifyNoMoreInteractions(client);
    }
    @Test void rejectedPagesCommitNoChangeToStoredPostings() {
        var from=LocalDate.of(2026,11,1);var to=LocalDate.of(2026,11,30);var period=new BackfillRange(from,to);
        var cases=new java.util.LinkedHashMap<String,Runnable>();
        // Expected month row after the failure: only pages accepted before it count (total|received|unique).
        var monthRow=new java.util.HashMap<String,String>();
        cases.put("BACKFILL_RANGE_MISMATCH",()->when(client.backfillList(from,to,1))
                .thenReturn(page(2,row(100,"20261101","바뀐 제목"),row(5000,"20261031","기간 밖"))));
        monthRow.put("BACKFILL_RANGE_MISMATCH","null|0|0");
        cases.put("BACKFILL_COUNT_CHANGED",()->{when(client.backfillList(from,to,1)).thenReturn(page(1001,1000,100,"20261101"));
                when(client.backfillList(from,to,2)).thenReturn(page(1002,row(100,"20261101","바뀐 제목"),row(5000,"20261101","새 공고")));});
        monthRow.put("BACKFILL_COUNT_CHANGED","1001|1000|1000");
        // sn 50 is new and checked before the duplicate 100: it must not reach the month's SN set.
        cases.put("BACKFILL_DUPLICATE_SN",()->{when(client.backfillList(from,to,1)).thenReturn(page(1002,1000,100,"20261101"));
                when(client.backfillList(from,to,2)).thenReturn(page(1002,row(50,"20261101","새 공고"),row(100,"20261101","바뀐 제목")));});
        monthRow.put("BACKFILL_DUPLICATE_SN","1002|1000|1000");
        cases.put("BACKFILL_PAGE_MISMATCH",()->when(client.backfillList(from,to,1))
                .thenReturn(page(3,row(100,"20261101","바뀐 제목"),row(5000,"20261101","새 공고"))));
        monthRow.put("BACKFILL_PAGE_MISMATCH","null|0|0");
        cases.forEach((expected,stub)->{
            jdbc.sql("TRUNCATE institution,collect_run RESTART IDENTITY CASCADE").update();
            ingestor.upsertPage(ingestor.parsePage(page(1,1,100,"20261101")),clock.instant());
            reset(client);stub.run();
            var stored="SELECT sn::text||'|'||title||'|'||content_hash FROM posting WHERE sn=100";
            String before=jdbc.sql(stored).query(String.class).single();
            assertThatThrownBy(()->collector().collect("rejected",period,false)).as(expected).hasMessage(expected);
            assertThat(jdbc.sql(stored).query(String.class).single()).as(expected).isEqualTo(before);
            assertThat(jdbc.sql("SELECT count(*) FROM posting WHERE sn IN (50,5000)").query(Integer.class).single()).as(expected).isZero();
            assertThat(jdbc.sql("SELECT count(*) FROM posting_change").query(Integer.class).single()).as(expected).isZero();
            assertThat(jdbc.sql("SELECT result FROM collect_run WHERE slot='rejected/2026-11-01'").query(String.class).single()).as(expected).isEqualTo("FAILED");
            assertThat(jdbc.sql("SELECT coalesce(total_count::text,'null')||'|'||received||'|'||unique_count FROM collect_run WHERE slot='rejected/2026-11-01'")
                    .query(String.class).single()).as(expected).isEqualTo(monthRow.get(expected));
        });
    }
    @Test void failedCommandKeepsCompletedMonthTotalsAndTheFailingMonthIsOnItsOwnRows() {
        var oct=LocalDate.of(2026,10,1);var nov=LocalDate.of(2026,11,1);var to=LocalDate.of(2026,11,30);var period=new BackfillRange(oct,to);
        when(client.backfillList(oct,LocalDate.of(2026,10,31),1)).thenReturn(page(1,1,10,"20261001"));
        when(client.backfillList(nov,to,1)).thenThrow(new CollectionFailure(CollectionFailure.Code.HTTP_STATUS))
                .thenThrow(new IllegalStateException("unexpected"));
        assertThatThrownBy(()->collector().collect("http",period,false)).hasMessage("HTTP_STATUS");
        assertThatThrownBy(()->collector().collect("crash",period,false)).hasMessage("INGESTION_FAILED");
        var row="SELECT result||'|'||error||'|'||coalesce(total_count::text,'null')||'|'||coalesce(received::text,'null')||'|'||coalesce(unique_count::text,'null') FROM collect_run WHERE slot=:slot";
        java.util.function.Function<String,String> record=slot->jdbc.sql(row).param("slot",slot).query(String.class).single();
        assertThat(record.apply("command/http")).isEqualTo("FAILED|HTTP_STATUS|1|1|1");
        assertThat(record.apply("command/crash")).as("October is reused from its month row").isEqualTo("FAILED|INGESTION_FAILED|1|1|1");
        assertThat(record.apply("http/2026-11-01")).isEqualTo("FAILED|HTTP_STATUS|null|0|0");
        assertThat(record.apply("http/2026-11-01/1")).isEqualTo("FAILED|HTTP_STATUS|null|null|null");
        assertThat(record.apply("crash/2026-11-01")).isEqualTo("FAILED|INGESTION_FAILED|null|0|0");
        assertThat(jdbc.sql("SELECT result FROM collect_run WHERE slot='http/2026-10-01'").query(String.class).single()).isEqualTo("COMPLETE");
    }
    @Test void officialEmptyPeriodResponseCompletesAMonthWithZeroPostings() throws Exception {
        var from=LocalDate.of(2010,1,1);var to=LocalDate.of(2010,1,31);
        when(client.backfillList(from,to,1)).thenReturn(Files.readString(Path.of("fixtures/alio-official-list-empty-sample.json")));
        assertThat(collector().collect("official-empty",new BackfillRange(from,to),false)).isEqualTo(BackfillCollector.Outcome.COMPLETE);
        assertThat(jdbc.sql("SELECT total_count||'|'||received||'|'||unique_count||'|'||received_sns::text FROM collect_run WHERE slot='official-empty/2010-01-01'")
                .query(String.class).single()).isEqualTo("0|0|0|[]");
    }
    @Test void resumesFailedPageSkipsCompletedMonthAndPreservesExactUniqueIdsWithAlreadyStoredRows() {
        var from=LocalDate.of(2026,10,1);var to=LocalDate.of(2026,11,30);var period=new BackfillRange(from,to);
        when(client.backfillList(from,LocalDate.of(2026,10,31),1)).thenReturn(page(1,1,10,"20261001"));
        when(client.backfillList(LocalDate.of(2026,11,1),to,1)).thenReturn(page(1001,1000,100,"20261101"));
        when(client.backfillList(LocalDate.of(2026,11,1),to,2)).thenReturn(page(1001,1,1100,"20261131"))
                .thenReturn(page(1001,1,1100,"20261101"));
        assertThatThrownBy(()->collector().collect("first",period,false)).isInstanceOf(IngestionException.class);
        assertThat(jdbc.sql("SELECT count(*) FROM posting").query(Integer.class).single()).isEqualTo(1001);
        assertThat(jdbc.sql("SELECT result FROM collect_run WHERE slot='first/2026-11-01'").query(String.class).single()).isEqualTo("INCOMPLETE");
        assertThat(jdbc.sql("SELECT error FROM collect_run WHERE slot='first/2026-11-01/2'").query(String.class).single()).isEqualTo("INVALID_ROW");
        assertThat(collector().collect("resume",period,false)).isEqualTo(BackfillCollector.Outcome.COMPLETE);
        assertThat(jdbc.sql("SELECT unique_count FROM collect_run WHERE slot='resume/2026-11-01'").query(Integer.class).single()).isEqualTo(1001);
        assertThat(jdbc.sql("SELECT unique_count FROM collect_run WHERE slot='command/resume'").query(Integer.class).single()).isEqualTo(1002);
        assertThat(jdbc.sql("SELECT count(*) FROM posting").query(Integer.class).single()).isEqualTo(1002);
        assertThat(collector().collect("resume",period,false)).isEqualTo(BackfillCollector.Outcome.ALREADY_CLAIMED);
        assertThat(collector().collect("all-skipped",period,false)).isEqualTo(BackfillCollector.Outcome.COMPLETE);
        verify(client).backfillList(from,LocalDate.of(2026,10,31),1);
        verify(client).backfillList(LocalDate.of(2026,11,1),to,1);
        verify(client,times(2)).backfillList(LocalDate.of(2026,11,1),to,2);verifyNoMoreInteractions(client);
        assertThat(jdbc.sql("SELECT count(*) FROM posting WHERE from_backfill=true AND detail_status='NONE'").query(Integer.class).single()).isEqualTo(1002);
    }
    @Test void periodUniqueCountUsesActualUnionEvenWhenAStartDateMovesAcrossMonths() {
        var from=LocalDate.of(2026,10,1);var to=LocalDate.of(2026,11,30);var period=new BackfillRange(from,to);
        when(client.backfillList(from,LocalDate.of(2026,10,31),1)).thenReturn(page(1,1,100,"20261001"));
        when(client.backfillList(LocalDate.of(2026,11,1),to,1)).thenReturn(page(1,1,100,"20261101"));
        assertThat(collector().collect("moving",period,false)).isEqualTo(BackfillCollector.Outcome.COMPLETE);
        assertThat(collector().collect("moving-resume",period,false)).isEqualTo(BackfillCollector.Outcome.COMPLETE);
        for(String slot:new String[]{"command/moving","command/moving-resume"}) {
            assertThat(jdbc.sql("SELECT received FROM collect_run WHERE slot=:slot").param("slot",slot).query(Integer.class).single()).isEqualTo(2);
            assertThat(jdbc.sql("SELECT unique_count FROM collect_run WHERE slot=:slot").param("slot",slot).query(Integer.class).single()).isEqualTo(1);
        }
        assertThat(jdbc.sql("SELECT count(*) FROM posting").query(Integer.class).single()).isEqualTo(1);
        verify(client).backfillList(from,LocalDate.of(2026,10,31),1);verify(client).backfillList(LocalDate.of(2026,11,1),to,1);
        verifyNoMoreInteractions(client);
    }
    @Test void rejectsDuplicateAcrossPagesAndChangedTotalWithoutCompletingMonth() {
        var from=LocalDate.of(2026,11,1);var to=LocalDate.of(2026,11,30);var period=new BackfillRange(from,to);
        when(client.backfillList(from,to,1)).thenReturn(page(1001,1000,100,"20261101"));
        when(client.backfillList(from,to,2)).thenReturn(page(1001,1,100,"20261101"))
                .thenReturn(page(1002,2,1100,"20261101"));
        assertThatThrownBy(()->collector().collect("duplicate",period,false)).hasMessage("BACKFILL_DUPLICATE_SN");
        assertThatThrownBy(()->collector().collect("changed",period,false)).hasMessage("BACKFILL_COUNT_CHANGED");
        assertThat(jdbc.sql("SELECT count(*) FROM collect_run WHERE page_no IS NULL AND result='COMPLETE'").query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM collect_run WHERE page_no=1 AND result='COMPLETE'").query(Integer.class).single()).isEqualTo(1);
    }
    @Test void explicitPageRestartReappliesExistingRowsAndCanFinishChangedIncompleteMonth() {
        var from=LocalDate.of(2026,11,1);var to=LocalDate.of(2026,11,30);var period=new BackfillRange(from,to);
        long saved=runs.claim("BACKFILL","older/page1").orElseThrow();runs.metadata(saved,from,to,from,to,1);
        ingestor.upsertPage(ingestor.parsePage(page(1,1,100,"20261101")),clock.instant());
        runs.completePage(saved,1001,1000,1000,0,0,java.util.stream.LongStream.range(100,1100).boxed().collect(java.util.stream.Collectors.toSet()));
        when(client.backfillList(from,to,1)).thenReturn(page(1,1,100,"20261101"));
        assertThat(collector().collect("restart",period,true)).isEqualTo(BackfillCollector.Outcome.COMPLETE);
        assertThat(jdbc.sql("SELECT inserted FROM collect_run WHERE slot='restart/2026-11-01/1'").query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT unique_count FROM collect_run WHERE slot='restart/2026-11-01'").query(Integer.class).single()).isEqualTo(1);
    }
    @Test void resumesAfterProcessExitBeforeOrAfterPageCheckpointWithoutLosingOrDuplicatingPostings() {
        var from=LocalDate.of(2026,11,1);var to=LocalDate.of(2026,11,30);var period=new BackfillRange(from,to);
        for(boolean checkpointCommitted:new boolean[]{false,true}) {
            jdbc.sql("TRUNCATE institution,collect_run RESTART IDENTITY CASCADE").update();
            reset(client);when(client.backfillList(from,to,1)).thenReturn(page(2,2,100,"20261101"));
            var interruptedRuns=new CollectionRuns(jdbc,clock,manager) {
                @Override public void completePage(long id,int total,int received,int inserted,int changed,int closed,java.util.Set<Long> sns) {
                    if(checkpointCommitted) super.completePage(id,total,received,inserted,changed,closed,sns);
                    throw new AssertionError("SIMULATED_PROCESS_EXIT");
                }
            };
            var interrupted=new BackfillCollector(interruptedRuns,client,ingestor,leases,clock);
            assertThatThrownBy(()->interrupted.collect("interrupted",period,false)).isInstanceOf(AssertionError.class);
            assertThat(jdbc.sql("SELECT count(*) FROM posting").query(Integer.class).single()).isEqualTo(2);
            assertThat(collector().collect("resumed",period,false)).isEqualTo(BackfillCollector.Outcome.COMPLETE);
            assertThat(jdbc.sql("SELECT count(*) FROM posting").query(Integer.class).single()).isEqualTo(2);
            assertThat(jdbc.sql("SELECT count(*) FROM posting_change").query(Integer.class).single()).isZero();
            assertThat(jdbc.sql("SELECT unique_count FROM collect_run WHERE slot='resumed/2026-11-01'").query(Integer.class).single()).isEqualTo(2);
            verify(client,times(checkpointCommitted?1:2)).backfillList(from,to,1);
            assertThat(jdbc.sql("SELECT count(*) FROM collect_run WHERE result IS NULL").query(Integer.class).single()).isGreaterThanOrEqualTo(2);
        }
    }
    @Test void zeroMonthWorksAndTwoManualSlotsCannotRunTheSameBackfillTogether() throws Exception {
        var from=LocalDate.of(2026,11,1);var to=LocalDate.of(2026,11,30);var period=new BackfillRange(from,to);
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        when(client.backfillList(from,to,1)).thenAnswer(invocation->{entered.countDown();release.await(10,java.util.concurrent.TimeUnit.SECONDS);return page(0,0,0,"20261101");});
        try(var pool=Executors.newFixedThreadPool(2)) {
            var first=pool.submit(()->collector().collect("one",period,false));assertThat(entered.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            try {assertThat(collector().collect("two",period,false)).isEqualTo(BackfillCollector.Outcome.ALREADY_RUNNING);}
            finally {release.countDown();}
            assertThat(first.get(30,java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(BackfillCollector.Outcome.COMPLETE);
        }
        verify(client).backfillList(from,to,1);verifyNoMoreInteractions(client);
        assertThat(jdbc.sql("SELECT unique_count FROM collect_run WHERE slot='one/2026-11-01'").query(Integer.class).single()).isZero();
    }
    @Test void outOfRangeRowsFailMonthAndNoHistoricalDetailIsRequested() {
        var from=LocalDate.of(2026,11,1);var to=LocalDate.of(2026,11,30);
        when(client.backfillList(from,to,1)).thenReturn(page(1,1,100,"20261031"));
        assertThatThrownBy(()->collector().collect("range",new BackfillRange(from,to),false)).hasMessage("BACKFILL_RANGE_MISMATCH");
        verify(client).backfillList(from,to,1);verifyNoMoreInteractions(client);
    }
}
