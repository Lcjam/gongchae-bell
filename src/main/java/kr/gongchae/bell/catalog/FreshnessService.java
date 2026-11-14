package kr.gongchae.bell.catalog;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class FreshnessService {
    public static final Duration DELAY_THRESHOLD = Duration.ofHours(2);
    public static final String EMPTY_DISPLAY = "아직 완전 수집 기록이 없습니다";
    public static final String UNAVAILABLE_DISPLAY = "일시적으로 수집 시각을 확인할 수 없습니다";

    private static final Logger log = LoggerFactory.getLogger(FreshnessService.class);
    private static final DateTimeFormatter DISPLAY_FORMAT = DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm 'KST'");

    private final JdbcClient jdbc;
    private final Clock clock;

    public FreshnessService(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public record FreshnessStatus(String display, boolean delayed, boolean available) {
        public static FreshnessStatus unavailable() {
            return new FreshnessStatus(UNAVAILABLE_DISPLAY, false, false);
        }
    }

    public String lastCollectedDisplay() {
        return currentStatus().display();
    }

    public FreshnessStatus currentStatus() {
        Optional<OffsetDateTime> completed = jdbc.sql(
                        "SELECT max(finished_at) FROM collect_run WHERE kind = 'REGULAR' AND result = 'COMPLETE'")
                .query(OffsetDateTime.class)
                .optional();
        return evaluate(completed, clock.instant());
    }

    public FreshnessStatus currentStatusSafe() {
        try {
            return currentStatus();
        } catch (DataAccessException ex) {
            log.warn("Failed to query collection freshness for view rendering: {}", ex.getClass().getSimpleName());
            return FreshnessStatus.unavailable();
        }
    }

    FreshnessStatus evaluate(Optional<OffsetDateTime> completed, Instant now) {
        if (completed.isEmpty()) {
            return new FreshnessStatus(EMPTY_DISPLAY, false, true);
        }
        OffsetDateTime finishedAt = completed.get();
        String formatted = finishedAt.atZoneSameInstant(clock.getZone()).format(DISPLAY_FORMAT);
        boolean delayed = Duration.between(finishedAt.toInstant(), now).compareTo(DELAY_THRESHOLD) > 0;
        return new FreshnessStatus(formatted, delayed, true);
    }
}
