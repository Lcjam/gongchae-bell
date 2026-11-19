package kr.gongchae.bell.alerts;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Operational knobs for asynchronous alert delivery (#7).
 * {@code workerConcurrency} defaults to 2 so at most 4 Hikari connections (A + R per worker) are held concurrently
 * out of the default 10-connection pool.
 */
@ConfigurationProperties("alerts.dispatch")
public record AlertDispatchProperties(int workerConcurrency, int batchSize, boolean schedulerEnabled) {
    public AlertDispatchProperties {
        if (workerConcurrency <= 0) workerConcurrency = 2;
        if (workerConcurrency > 4) throw new IllegalArgumentException("ALERTS_WORKER_CONCURRENCY_TOO_HIGH");
        if (batchSize <= 0) batchSize = 20;
    }
}
