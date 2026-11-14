package kr.gongchae.bell.ingestion.runtime;

import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="collector.enabled",havingValue="true")
public class CollectorScheduler {
    private final RegularCollector collector;
    private final Clock clock;
    public CollectorScheduler(RegularCollector collector,Clock clock) {this.collector=collector;this.clock=clock;}
    @Scheduled(cron="0 7 * * * *",zone="Asia/Seoul")
    public void collect() {collector.collect(clock.instant());}
}
