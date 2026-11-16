package kr.gongchae.bell.ingestion.runtime;

import java.time.Clock;
import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="collector.backfill",havingValue="true")
public class BackfillCommand implements ApplicationRunner {
    private static final Logger log=LoggerFactory.getLogger(BackfillCommand.class);
    private final CollectorProperties properties;
    private final BackfillCollector collector;
    private final Clock clock;
    private final ConfigurableApplicationContext context;
    public BackfillCommand(CollectorProperties properties,BackfillCollector collector,Clock clock,ConfigurableApplicationContext context) {
        this.properties=properties;this.collector=collector;this.clock=clock;this.context=context;
    }
    @Override public void run(ApplicationArguments arguments) {
        try {
            LocalDate today=LocalDate.now(clock.withZone(ApiBudget.KST));
            LocalDate from=date(properties.backfillFrom(),today.minusYears(3));
            LocalDate to=date(properties.backfillTo(),today);
            var result=collector.collect(properties.backfillSlot(),new BackfillRange(from,to),properties.backfillRestartPages());
            log.info("BACKFILL_{}",result);
        } finally {context.close();}
    }
    private static LocalDate date(String value,LocalDate fallback) {
        if(value==null || value.isBlank()) return fallback;
        try {return LocalDate.parse(value);} catch(java.time.DateTimeException invalid) {throw new IllegalArgumentException("INVALID_BACKFILL_RANGE");}
    }
}
