package kr.gongchae.bell.ingestion.runtime;

import java.time.Clock;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;
import kr.gongchae.bell.ingestion.IngestionException;
import kr.gongchae.bell.ingestion.PostingIngestor;

/**
 * Initial load (START_SCAN): reads every page of list?pbancBgngYmd=from until the upstream totalCount is received,
 * checks every raw row, and applies only rows that started by {@code to}. Rows: command, one scan, its pages.
 */
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
    /**
     * Without restartPages a new slot reuses the period's latest scan: a COMPLETE one only through its own row's two
     * SN sets (no HTTP), otherwise its generation's leading run of completed pages. restartPages starts a new generation.
     */
    public Outcome collect(String slot,BackfillRange period,boolean restartPages) {
        if(slot==null || !slot.matches("[A-Za-z0-9_-]{1,40}")) throw new IllegalArgumentException("BACKFILL_SLOT_REQUIRED");
        var acquired=leases.acquire(AdvisoryLease.BACKFILL);
        if(acquired.isEmpty()) return Outcome.ALREADY_RUNNING;
        try(var lease=acquired.get()) {
            var command=runs.claim("BACKFILL","command/"+slot);
            if(command.isEmpty()) return Outcome.ALREADY_CLAIMED;
            runs.metadata(command.get(),period.from(),period.to(),null,null,null,null);
            var latest=restartPages?Optional.<CollectionRuns.Scan>empty():runs.latestScan(period.from(),period.to());
            if(latest.isPresent() && latest.get().completed()!=null) {
                var done=latest.get();var own=done.completed();
                if(own.sns().size()!=done.unique() || own.applied().size()!=done.applied()) {
                    runs.fail(command.get(),CollectionFailure.Code.BACKFILL_PAGE_MISMATCH);
                    throw new CollectionFailure(CollectionFailure.Code.BACKFILL_PAGE_MISMATCH);
                }
                runs.finish(command.get(),"COMPLETE",null,own.total(),own.received(),own.sns().size(),own.applied().size());
                return Outcome.COMPLETE;
            }
            long scan=runs.claim("BACKFILL",slot+"/scan").orElseThrow();
            long generation=latest.map(CollectionRuns.Scan::generation).orElse(scan);
            runs.metadata(scan,period.from(),period.to(),period.from(),period.to(),null,generation);
            var progress=new Progress();
            try {
                boolean live=false;
                for(int number=1;;number++) {
                    lease.verify();
                    // Only the generation's leading completed pages; once a page is fetched, every later page is fetched too.
                    var checkpoint=live?Optional.<CollectionRuns.Page>empty():runs.completedPage(generation,number);
                    CollectionRuns.Page accepted;
                    if(checkpoint.isPresent()) {
                        accepted=checkpoint.get();
                        validate(accepted,number,progress);
                    } else {
                        live=true;
                        accepted=fetch(slot,period,generation,number,progress);
                    }
                    // Counted only once checked, applied and checkpointed: a rejected page leaves the totals as they were.
                    progress.expected=accepted.total();progress.received+=accepted.received();
                    progress.seen.addAll(accepted.sns());progress.applied.addAll(accepted.applied());
                    if(progress.received==progress.expected) break;
                }
                if(progress.seen.size()!=progress.expected) throw new CollectionFailure(CollectionFailure.Code.BACKFILL_DUPLICATE_SN);
                var result=new CollectionRuns.Page(progress.expected,progress.received,Set.copyOf(progress.seen),Set.copyOf(progress.applied));
                runs.completeScan(scan,result);
                runs.finish(command.get(),"COMPLETE",null,result.total(),result.received(),result.sns().size(),result.applied().size());
                return Outcome.COMPLETE;
            } catch(IngestionException failure) {
                progress.finish(scan,command.get(),failure.failure().result(),failure.failure().name());throw failure;
            } catch(CollectionFailure failure) {
                progress.finish(scan,command.get(),"FAILED",failure.code().name());throw failure;
            } catch(RuntimeException failure) {
                progress.finish(scan,command.get(),"FAILED",CollectionFailure.Code.INGESTION_FAILED.name());
                throw new CollectionFailure(CollectionFailure.Code.INGESTION_FAILED);
            }
        }
    }
    private CollectionRuns.Page fetch(String slot,BackfillRange period,long generation,int number,Progress progress) {
        long page=runs.claim("BACKFILL",slot+"/scan/"+number).orElseThrow();
        runs.metadata(page,period.from(),period.to(),period.from(),period.to(),number,generation);
        try {
            // One parse validates every raw row; all checks run before upsertPage, so a rejected page changes nothing.
            var parsed=ingestor.parsePage(client.backfillList(period.from(),number));
            var raw=new CollectionRuns.Page(parsed.totalCount(),parsed.sns().size(),parsed.sns(),Set.of());
            validate(raw,number,progress);
            if(!parsed.beginsWithin(period.from(),LocalDate.MAX)) throw new CollectionFailure(CollectionFailure.Code.BACKFILL_RANGE_MISMATCH);
            var target=parsed.startingOnOrBefore(period.to());
            var counts=ingestor.upsertPage(target,clock.instant());
            var accepted=new CollectionRuns.Page(raw.total(),raw.received(),raw.sns(),target.sns());
            runs.completePage(page,accepted,counts.inserted(),counts.changed(),counts.closed());
            return accepted;
        } catch(IngestionException failure) {
            runs.finish(page,failure.failure().result(),failure.failure().name(),null,null,null);throw failure;
        } catch(CollectionFailure failure) {
            runs.fail(page,failure.code());throw failure;
        } catch(RuntimeException failure) {
            runs.fail(page,CollectionFailure.Code.INGESTION_FAILED);throw new CollectionFailure(CollectionFailure.Code.INGESTION_FAILED);
        }
    }
    /** Checks a raw page against the pages accepted so far without changing them. */
    private static void validate(CollectionRuns.Page page,int number,Progress progress) {
        if(progress.expected>=0 && progress.expected!=page.total()) throw new CollectionFailure(CollectionFailure.Code.BACKFILL_COUNT_CHANGED);
        long remaining=(long)page.total()-(number-1L)*1000;
        int wanted=(int)Math.min(1000,Math.max(0,remaining));
        if(page.total()<0 || page.received()!=wanted || page.sns().size()!=page.received())
            throw new CollectionFailure(CollectionFailure.Code.BACKFILL_PAGE_MISMATCH);
        for(long sn:page.sns()) if(progress.seen.contains(sn)) throw new CollectionFailure(CollectionFailure.Code.BACKFILL_DUPLICATE_SN);
    }
    /** Raw totals and SNs of accepted pages, and the SNs applied from them. */
    private final class Progress {
        int expected=-1,received=0;
        final Set<Long> seen=new HashSet<>(),applied=new HashSet<>();
        void finish(long scan,long command,String result,String error) {
            for(long id:new long[]{scan,command}) runs.finish(id,result,error,expected<0?null:expected,received,seen.size(),applied.size());
        }
    }
}
