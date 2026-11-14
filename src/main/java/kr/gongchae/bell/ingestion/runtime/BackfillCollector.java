package kr.gongchae.bell.ingestion.runtime;

import java.time.Clock;
import java.util.HashSet;
import java.util.Set;
import org.springframework.stereotype.Component;
import kr.gongchae.bell.ingestion.IngestionException;
import kr.gongchae.bell.ingestion.PostingIngestor;

@Component
public class BackfillCollector {
    public enum Outcome { COMPLETE, ALREADY_CLAIMED, ALREADY_RUNNING }
    private final CollectionRuns runs;
    private final RecruitmentClient client;
    private final PostingIngestor ingestor;
    private final AdvisoryLease leases;
    private final Clock clock;
    public BackfillCollector(CollectionRuns runs,RecruitmentClient client,PostingIngestor ingestor,AdvisoryLease leases,Clock clock) {
        this.runs=runs;this.client=client;this.ingestor=ingestor;this.leases=leases;this.clock=clock;
    }
    public Outcome collect(String slot,BackfillRange period,boolean restartPages) {
        if(slot==null || !slot.matches("[A-Za-z0-9_-]{1,40}")) throw new IllegalArgumentException("BACKFILL_SLOT_REQUIRED");
        var acquired=leases.acquire(AdvisoryLease.BACKFILL);
        if(acquired.isEmpty()) return Outcome.ALREADY_RUNNING;
        try(var lease=acquired.get()) {
            var run=runs.claim("BACKFILL","command/"+slot);
            if(run.isEmpty()) return Outcome.ALREADY_CLAIMED;
            runs.metadata(run.get(),period.from(),period.to(),null,null,null);
            int total=0,received=0;
            Set<Long> periodSns=new HashSet<>();
            try {
                for(BackfillRange month:period.months()) {
                    lease.verify();
                    var completed=runs.completedMonthSummary(month.from(),month.to());
                    if(completed.isPresent()) {
                        Set<Long> sns=runs.completedMonthSns(month.from(),month.to());
                        if(sns.size()!=completed.get().unique()) throw new CollectionFailure(CollectionFailure.Code.BACKFILL_PAGE_MISMATCH);
                        total+=completed.get().total();received+=completed.get().received();periodSns.addAll(sns);
                        continue;
                    }
                    var receipt=month(slot,period,month,restartPages,lease);
                    total+=receipt.total();received+=receipt.received();periodSns.addAll(receipt.sns());
                }
                runs.finish(run.get(),"COMPLETE",null,total,received,periodSns.size());
                return Outcome.COMPLETE;
            } catch(IngestionException failure) {
                runs.finish(run.get(),failure.failure().result(),failure.failure().name(),total,received,periodSns.size());throw failure;
            } catch(CollectionFailure failure) {
                runs.fail(run.get(),failure.code());throw failure;
            } catch(RuntimeException failure) {
                runs.fail(run.get(),CollectionFailure.Code.INGESTION_FAILED);throw new CollectionFailure(CollectionFailure.Code.INGESTION_FAILED);
            }
        }
    }
    private CollectionRuns.Page month(String slot,BackfillRange period,BackfillRange month,boolean restartPages,AdvisoryLease.Lease lease) {
        long run=runs.claim("BACKFILL",slot+"/"+month.from()).orElseThrow();
        runs.metadata(run,period.from(),period.to(),month.from(),month.to(),null);
        int expected=-1,received=0;
        Set<Long> seen=new HashSet<>();
        try {
            for(int number=1;;number++) {
                lease.verify();
                var checkpoint=restartPages?java.util.Optional.<CollectionRuns.Page>empty():runs.completedPage(month.from(),month.to(),number);
                if(checkpoint.isPresent()) {
                    var saved=checkpoint.get();
                    expected=validate(saved,number,expected,seen);received+=saved.received();
                } else {
                    long page=runs.claim("BACKFILL",slot+"/"+month.from()+"/"+number).orElseThrow();
                    runs.metadata(page,period.from(),period.to(),month.from(),month.to(),number);
                    try {
                        var result=ingestor.upsertPage(client.backfillList(month.from(),month.to(),number),clock.instant());
                        var counts=result.counts();
                        var receipt=new CollectionRuns.Page(counts.totalCount(),counts.received(),result.sns());
                        expected=validate(receipt,number,expected,seen);
                        if(!runs.withinRange(result.sns(),month.from(),month.to())) throw new CollectionFailure(CollectionFailure.Code.BACKFILL_RANGE_MISMATCH);
                        runs.completePage(page,counts.totalCount(),counts.received(),counts.inserted(),counts.changed(),counts.closed(),result.sns());
                        received+=counts.received();
                    } catch(IngestionException failure) {
                        runs.finish(page,failure.failure().result(),failure.failure().name(),null,null,null);throw failure;
                    } catch(CollectionFailure failure) {
                        runs.fail(page,failure.code());throw failure;
                    } catch(RuntimeException failure) {
                        runs.fail(page,CollectionFailure.Code.INGESTION_FAILED);throw new CollectionFailure(CollectionFailure.Code.INGESTION_FAILED);
                    }
                }
                if(received==expected) break;
            }
            if(seen.size()!=expected) throw new CollectionFailure(CollectionFailure.Code.BACKFILL_DUPLICATE_SN);
            runs.finish(run,"COMPLETE",null,expected,received,seen.size());return new CollectionRuns.Page(expected,received,Set.copyOf(seen));
        } catch(IngestionException failure) {
            runs.finish(run,failure.failure().result(),failure.failure().name(),expected<0?null:expected,received,seen.size());throw failure;
        } catch(CollectionFailure failure) {
            runs.finish(run,"FAILED",failure.code().name(),expected<0?null:expected,received,seen.size());throw failure;
        } catch(RuntimeException failure) {
            runs.fail(run,CollectionFailure.Code.INGESTION_FAILED);throw new CollectionFailure(CollectionFailure.Code.INGESTION_FAILED);
        }
    }
    private int validate(CollectionRuns.Page page,int number,int expected,Set<Long> seen) {
        if(expected>=0 && expected!=page.total()) throw new CollectionFailure(CollectionFailure.Code.BACKFILL_COUNT_CHANGED);
        long remaining=(long)page.total()-(number-1L)*1000;
        int wanted=(int)Math.min(1000,Math.max(0,remaining));
        if(page.total()<0 || page.received()!=wanted || page.sns().size()!=page.received())
            throw new CollectionFailure(CollectionFailure.Code.BACKFILL_PAGE_MISMATCH);
        for(long sn:page.sns()) if(!seen.add(sn)) throw new CollectionFailure(CollectionFailure.Code.BACKFILL_DUPLICATE_SN);
        return page.total();
    }
}
