package kr.gongchae.bell.alerts;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Exposes package-private {@link AlertsMetrics} sample recording for {@code MonitoringContractIntegrationTest}
 * so the real Prometheus exporter emits dynamic counter/timer series without relaxing production visibility.
 */
public final class AlertsMetricsTestHelper {
    private AlertsMetricsTestHelper() {}

    public static void recordSampleDeliveryMeters(AlertsMetrics metrics) {
        OffsetDateTime created = OffsetDateTime.of(2026, 11, 19, 0, 0, 0, 0, ZoneOffset.UTC);
        metrics.recordAttemptOutcome("DISCORD_NEW", SendOutcome.SUCCESS);
        metrics.recordDuplicateRisk("DISCORD_NEW");
        metrics.recordDeliveryLatency("DISCORD_NEW", created, created.plusSeconds(2));
        metrics.recordCollectionToDeliveryLatency("DISCORD_NEW", created.minusSeconds(5), created.plusSeconds(2));
    }
}
