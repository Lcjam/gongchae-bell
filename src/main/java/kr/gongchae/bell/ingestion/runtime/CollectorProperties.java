package kr.gongchae.bell.ingestion.runtime;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("collector")
public record CollectorProperties(boolean enabled, String serviceKey, int warningLimit, int stopLimit,
        boolean backfill, String backfillSlot, String backfillFrom, String backfillTo, boolean backfillRestartPages) {
    public CollectorProperties {
        if (warningLimit == 0) warningLimit = 500;
        if (stopLimit == 0) stopLimit = 900;
        if (warningLimit < 1 || stopLimit <= warningLimit || stopLimit > 900)
            throw new IllegalArgumentException("INVALID_BUDGET_LIMITS");
        if (enabled && backfill) throw new IllegalArgumentException("BACKFILL_REQUIRES_SCHEDULER_DISABLED");
        if (backfill && (backfillSlot == null || !backfillSlot.matches("[A-Za-z0-9_-]{1,40}")))
            throw new IllegalArgumentException("BACKFILL_SLOT_REQUIRED");
        if ((enabled || backfill) && (serviceKey == null || serviceKey.isBlank()))
            throw new IllegalArgumentException("SERVICE_KEY_REQUIRED");
    }
    @Override public String toString() { return "CollectorProperties[enabled=" + enabled + "]"; }
}
