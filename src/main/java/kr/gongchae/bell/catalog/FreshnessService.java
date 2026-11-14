package kr.gongchae.bell.catalog;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class FreshnessService {
    private static final DateTimeFormatter DISPLAY_FORMAT = DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm 'KST'");

    private final JdbcClient jdbc;
    private final Clock clock;

    public FreshnessService(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public String lastCollectedDisplay() {
        var completed = jdbc.sql(
                        "SELECT max(finished_at) FROM collect_run WHERE kind = 'REGULAR' AND result = 'COMPLETE'")
                .query(OffsetDateTime.class)
                .optional();
        return completed
                .map(time -> time.atZoneSameInstant(clock.getZone()).format(DISPLAY_FORMAT))
                .orElse("아직 완전 수집 기록이 없습니다");
    }
}
