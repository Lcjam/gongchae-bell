package kr.gongchae.bell.ingestion.runtime;

import java.time.Clock;
import java.time.LocalDate;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Gauges read durable state, so restarts do not erase the operational picture. */
@Component
public class CollectorMetrics {
    public CollectorMetrics(MeterRegistry registry,JdbcClient jdbc,Clock clock) {
        Gauge.builder("collector.api.attempts.today",jdbc,j -> j.sql("SELECT COALESCE((SELECT count FROM api_budget WHERE day=:day),0)")
                .param("day",LocalDate.now(clock.withZone(ApiBudget.KST))).query(Integer.class).single()).register(registry);
        Gauge.builder("collector.last.complete.epoch",jdbc,j -> j.sql("""
                SELECT COALESCE(extract(epoch FROM max(finished_at)),0) FROM collect_run WHERE kind='REGULAR' AND result='COMPLETE'
                """).query(Double.class).single()).register(registry);
        Gauge.builder("collector.regular.incomplete.consecutive",jdbc,j -> j.sql("""
                SELECT count(*) FROM collect_run WHERE kind='REGULAR' AND id > COALESCE(
                  (SELECT max(id) FROM collect_run WHERE kind='REGULAR' AND result='COMPLETE'),0)
                """).query(Integer.class).single()).register(registry);
        Gauge.builder("collector.regular.claimed.last24h",jdbc,j -> j.sql("""
                SELECT count(*) FROM collect_run WHERE kind='REGULAR' AND started_at > now()-interval '24 hours'
                """).query(Integer.class).single()).register(registry);
        Gauge.builder("collector.regular.complete.last24h",jdbc,j -> j.sql("""
                SELECT count(*) FROM collect_run WHERE kind='REGULAR' AND result='COMPLETE' AND started_at > now()-interval '24 hours'
                """).query(Integer.class).single()).register(registry);
        Gauge.builder("collector.api.retries.today",jdbc,j -> j.sql("SELECT count(*) FROM api_attempt WHERE day=:day AND attempt_no>1")
                .param("day",LocalDate.now(clock.withZone(ApiBudget.KST))).query(Integer.class).single()).register(registry);
        Gauge.builder("collector.api.unfinished.today",jdbc,j -> j.sql("SELECT count(*) FROM api_attempt WHERE day=:day AND outcome IS NULL")
                .param("day",LocalDate.now(clock.withZone(ApiBudget.KST))).query(Integer.class).single()).register(registry);
        for(String kind:new String[]{"REGULAR","BACKFILL","DETAIL"}) {
            Gauge.builder("collector.api.attempts.kind.today",jdbc,j -> j.sql("SELECT count(*) FROM api_attempt WHERE day=:day AND kind=:kind")
                    .param("day",LocalDate.now(clock.withZone(ApiBudget.KST))).param("kind",kind).query(Integer.class).single())
                    .tag("kind",kind).register(registry);
        }
        Gauge.builder("collector.runs.unfinished",jdbc,j -> j.sql("SELECT count(*) FROM collect_run WHERE result IS NULL")
                .query(Integer.class).single()).register(registry);
    }
}
