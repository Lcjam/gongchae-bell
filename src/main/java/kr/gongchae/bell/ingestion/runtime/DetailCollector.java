package kr.gongchae.bell.ingestion.runtime;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public class DetailCollector {
    /** Initial values (ADR-0014): a posting stops being retried after this many failed detail runs. */
    static final int MAX_FAILURES=3;
    /** Initial values (ADR-0014): one sweep never holds the hourly schedule longer than this. */
    static final int MAX_PER_SWEEP=30;
    static final Duration MAX_SWEEP=Duration.ofMinutes(10);
    /** Failed detail runs of posting p. A budget stop or shutdown is not the posting's failure. */
    static final String FAILURES="""
            (SELECT count(*) FROM collect_run r WHERE r.kind='DETAIL' AND r.slot LIKE '%/' || p.sn
              AND r.result='FAILED' AND r.error NOT IN ('BUDGET_EXHAUSTED','INTERRUPTED'))""";
    /** Open postings first seen by regular collection whose detail is still missing. */
    static final String MISSING="p.status='OPEN' AND p.from_backfill=false AND p.detail_status<>'DONE'";
    private final JdbcClient jdbc;
    private final RecruitmentClient client;
    private final CollectionRuns runs;
    private final AdvisoryLease leases;
    private final Clock clock;
    private final JsonMapper json=JsonMapper.builder().build();
    public DetailCollector(JdbcClient jdbc,RecruitmentClient client,CollectionRuns runs,AdvisoryLease leases,Clock clock) {
        this.jdbc=jdbc;this.client=client;this.runs=runs;this.leases=leases;this.clock=clock;
    }
    public void collect(String regularSlot) {
        var acquired=leases.acquire(AdvisoryLease.DETAILS);
        if(acquired.isEmpty()) return;
        try(var lease=acquired.get()) {
            var pending=jdbc.sql("SELECT p.sn FROM posting p WHERE "+MISSING+" AND "+FAILURES+" < :max ORDER BY p.sn LIMIT :limit")
                    .param("max",MAX_FAILURES).param("limit",MAX_PER_SWEEP).query(Long.class).list();
            var deadline=clock.instant().plus(MAX_SWEEP);
            for(long sn:pending) {
                if(clock.instant().isAfter(deadline)) return;
                lease.verify();
                var run=runs.claim("DETAIL",regularSlot+"/"+sn);
                if(run.isEmpty()) continue;
                try {
                    save(sn,client.detail(sn));
                    runs.finish(run.get(),"COMPLETE",null,1,1,1);
                } catch(CollectionFailure failure) {
                    if(failure.code()!=CollectionFailure.Code.BUDGET_EXHAUSTED) failed(sn);
                    runs.fail(run.get(),failure.code());
                    if(failure.code()==CollectionFailure.Code.BUDGET_EXHAUSTED || failure.code()==CollectionFailure.Code.INTERRUPTED) return;
                } catch(RuntimeException failure) {
                    runs.fail(run.get(),CollectionFailure.Code.INGESTION_FAILED);
                }
            }
        }
    }
    void save(long sn,String body) {
        try {
            JsonNode root=json.readTree(body);
            if(root==null || !root.isObject() || !"200".equals(root.path("resultCode").asString())) throw invalid();
            JsonNode item=root.path("result");
            if(!item.isObject() || !Long.toString(sn).equals(item.path("recrutPblntSn").asString())
                    || !item.path("steps").isArray() || !item.path("files").isArray()) throw invalid();
            var steps=json.createArrayNode(); var files=json.createArrayNode();
            for(JsonNode element:item.path("steps")) {var value=element.has("step")?element.path("step"):element;
                if(!value.isObject()) throw invalid();steps.add(value);}
            for(JsonNode element:item.path("files")) {var value=element.has("file")?element.path("file"):element;
                if(!value.isObject()) throw invalid();files.add(value);}
            jdbc.sql("""
                    UPDATE posting SET steps=CAST(:steps AS jsonb), files=CAST(:files AS jsonb), detail_status='DONE',
                      detail_collected_at=:at WHERE sn=:sn AND detail_status<>'DONE'
                    """).param("steps",json.writeValueAsString(steps)).param("files",json.writeValueAsString(files))
                    .param("at",OffsetDateTime.now(clock)).param("sn",sn).update();
        } catch(CollectionFailure failure) {
            failed(sn); throw failure;
        } catch(tools.jackson.core.JacksonException failure) {
            failed(sn); throw invalid();
        }
    }
    private void failed(long sn) {
        jdbc.sql("UPDATE posting SET detail_status='FAILED' WHERE sn=:sn AND detail_status<>'DONE'").param("sn",sn).update();
    }
    private static CollectionFailure invalid() {return new CollectionFailure(CollectionFailure.Code.INVALID_DETAIL);}
}
