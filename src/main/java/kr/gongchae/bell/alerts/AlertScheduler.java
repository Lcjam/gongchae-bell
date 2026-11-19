package kr.gongchae.bell.alerts;

import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodic scheduler for daily batch catch-up (08:00 KST digest / 09:00 KST deadline) and asynchronous worker ticks.
 * Disabled cleanly in deterministic integration tests via {@code alerts.dispatch.scheduler-enabled=false}.
 */
@Component
@ConditionalOnProperty(name = "alerts.dispatch.scheduler-enabled", havingValue = "true", matchIfMissing = true)
public class AlertScheduler {
    private static final Logger log = LoggerFactory.getLogger(AlertScheduler.class);

    private final AlertsProperties properties;
    private final AlertBatchGenerator batchGenerator;
    private final AlertWorker worker;
    private final Clock clock;

    public AlertScheduler(AlertsProperties properties, AlertBatchGenerator batchGenerator, AlertWorker worker,
            Clock clock) {
        this.properties = properties;
        this.batchGenerator = batchGenerator;
        this.worker = worker;
        this.clock = clock;
    }

    @Scheduled(cron = "0 * * * * *", zone = "Asia/Seoul")
    public void scheduleDailyBatches() {
        if (!properties.enabled()) return;
        try {
            batchGenerator.runDigestIfDue(clock.instant());
        } catch (RuntimeException failure) {
            log.warn("ALERT_DIGEST_SCHEDULE_FAILED {}", failure.getClass().getSimpleName());
        }
        try {
            batchGenerator.runDeadlineIfDue(clock.instant());
        } catch (RuntimeException failure) {
            log.warn("ALERT_DEADLINE_SCHEDULE_FAILED {}", failure.getClass().getSimpleName());
        }
    }

    @Scheduled(fixedDelayString = "${alerts.dispatch.poll-interval:PT5S}",
            initialDelayString = "${alerts.dispatch.poll-interval:PT5S}")
    public void pollDispatch() {
        if (!properties.enabled()) return;
        try {
            worker.runOnce();
        } catch (RuntimeException failure) {
            log.warn("ALERT_WORKER_SCHEDULE_FAILED {}", failure.getClass().getSimpleName());
        }
    }
}
