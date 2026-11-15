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
                    var completed=runs.completedMonth(month.from(),month.to());
                    if(completed.isPresent()) {
                        // The month row's own SN snapshot, not a re-union of page rows from older attempts.
                        var done=completed.get();
                        if(done.sns().size()!=done.unique()) throw new CollectionFailure(CollectionFailure.Code.BACKFILL_PAGE_MISMATCH);
                        total+=done.total();received+=done.received();periodSns.addAll(done.sns());
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
                // Totals cover completed months only; the failing month's detail is on its month and page rows.
                runs.finish(run.get(),"FAILED",failure.code().name(),total,received,periodSns.size());throw failure;
            } catch(RuntimeException failure) {
                runs.finish(run.get(),"FAILED",CollectionFailure.Code.INGESTION_FAILED.name(),total,received,periodSns.size());
                throw new CollectionFailure(CollectionFailure.Code.INGESTION_FAILED);
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
                CollectionRuns.Page accepted;
                if(checkpoint.isPresent()) {
                    accepted=checkpoint.get();
                    validate(accepted,number,expected,seen);
                } else {
                    long page=runs.claim("BACKFILL",slot+"/"+month.from()+"/"+number).orElseThrow();
                    runs.metadata(page,period.from(),period.to(),month.from(),month.to(),number);
                    try {
                        // Every check runs before upsertPage, so a rejected page changes no posting (contract v3).
                        var parsed=ingestor.parsePage(client.backfillList(month.from(),month.to(),number));
                        accepted=new CollectionRuns.Page(parsed.totalCount(),parsed.sns().size(),parsed.sns());
                        validate(accepted,number,expected,seen);
                        if(!parsed.beginsWithin(month.from(),month.to())) throw new CollectionFailure(CollectionFailure.Code.BACKFILL_RANGE_MISMATCH);
                        var counts=ingestor.upsertPage(parsed,clock.instant());
                        runs.completePage(page,counts.totalCount(),counts.received(),counts.inserted(),counts.changed(),counts.closed(),parsed.sns());
                    } catch(IngestionException failure) {
                        runs.finish(page,failure.failure().result(),failure.failure().name(),null,null,null);throw failure;
                    } catch(CollectionFailure failure) {
                        runs.fail(page,failure.code());throw failure;
                    } catch(RuntimeException failure) {
                        runs.fail(page,CollectionFailure.Code.INGESTION_FAILED);throw new CollectionFailure(CollectionFailure.Code.INGESTION_FAILED);
                    }
                }
                // Counted only once checked, applied and checkpointed: a rejected page leaves the month's totals as they were.
                expected=accepted.total();received+=accepted.received();seen.addAll(accepted.sns());
                if(received==expected) break;
            }
            if(seen.size()!=expected) throw new CollectionFailure(CollectionFailure.Code.BACKFILL_DUPLICATE_SN);
            runs.completeMonth(run,expected,received,seen);return new CollectionRuns.Page(expected,received,Set.copyOf(seen));
        } catch(IngestionException failure) {
            runs.finish(run,failure.failure().result(),failure.failure().name(),expected<0?null:expected,received,seen.size());throw failure;
        } catch(CollectionFailure failure) {
            runs.finish(run,"FAILED",failure.code().name(),expected<0?null:expected,received,seen.size());throw failure;
        } catch(RuntimeException failure) {
            runs.finish(run,"FAILED",CollectionFailure.Code.INGESTION_FAILED.name(),expected<0?null:expected,received,seen.size());
            throw new CollectionFailure(CollectionFailure.Code.INGESTION_FAILED);
        }
    }
    /** Checks a page against the pages accepted so far without changing them. */
    private static void validate(CollectionRuns.Page page,int number,int expected,Set<Long> seen) {
        if(expected>=0 && expected!=page.total()) throw new CollectionFailure(CollectionFailure.Code.BACKFILL_COUNT_CHANGED);
        long remaining=(long)page.total()-(number-1L)*1000;
        int wanted=(int)Math.min(1000,Math.max(0,remaining));
        if(page.total()<0 || page.received()!=wanted || page.sns().size()!=page.received())
            throw new CollectionFailure(CollectionFailure.Code.BACKFILL_PAGE_MISMATCH);
        for(long sn:page.sns()) if(seen.contains(sn)) throw new CollectionFailure(CollectionFailure.Code.BACKFILL_DUPLICATE_SN);
    }
}
