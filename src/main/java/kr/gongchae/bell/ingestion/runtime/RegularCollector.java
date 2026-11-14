package kr.gongchae.bell.ingestion.runtime;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import kr.gongchae.bell.ingestion.PostingIngestor;

@Component
public class RegularCollector {
    private static final Logger log=LoggerFactory.getLogger(RegularCollector.class);
    private final CollectionRuns runs;
    private final RecruitmentClient client;
    private final PostingIngestor ingestor;
    private final DetailCollector details;
    private final Clock clock;
    public RegularCollector(CollectionRuns runs,RecruitmentClient client,PostingIngestor ingestor,DetailCollector details,Clock clock) {
        this.runs=runs;this.client=client;this.ingestor=ingestor;this.details=details;this.clock=clock;
    }
    public boolean collect(Instant scheduledAt) {
        String slot=slot(scheduledAt);
        var run=runs.claim("REGULAR",slot);
        if(run.isEmpty()) return false;
        try {
            String body=client.regularList();
            var result=ingestor.applyRegular(run.get(),body,clock.instant());
            if(!"COMPLETE".equals(result.result())) return false;
        } catch(CollectionFailure failure) {
            runs.fail(run.get(),failure.code()); log.warn("COLLECTION_FAILED {}",failure.code());return false;
        } catch(RuntimeException failure) {
            // The core may already have committed APPLY_ERROR; never overwrite a finished run.
            runs.fail(run.get(),CollectionFailure.Code.INGESTION_FAILED);log.warn("COLLECTION_FAILED INGESTION_FAILED");return false;
        }
        details.collect(slot);
        return true;
    }
    static String slot(Instant instant) {return instant.atZone(ApiBudget.KST).truncatedTo(ChronoUnit.HOURS).toOffsetDateTime().toString();}
}
