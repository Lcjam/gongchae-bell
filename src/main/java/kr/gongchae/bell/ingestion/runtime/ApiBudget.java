package kr.gongchae.bell.ingestion.runtime;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class ApiBudget {
    public static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final Logger log = LoggerFactory.getLogger(ApiBudget.class);
    private final JdbcClient jdbc;
    private final TransactionTemplate independent;
    private final Clock clock;
    private final CollectorProperties properties;
    public ApiBudget(JdbcClient jdbc, PlatformTransactionManager manager, Clock clock, CollectorProperties properties) {
        this.jdbc = jdbc; this.clock = clock; this.properties = properties;
        independent = new TransactionTemplate(manager);
        independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    public long reserve(String kind) { return reserve(kind,1); }
    public long reserve(String kind,int attempt) {
        if(attempt<1 || attempt>3) throw new IllegalArgumentException("INVALID_ATTEMPT");
        return independent.execute(status -> {
            LocalDate day = LocalDate.now(clock.withZone(KST));
            var count = jdbc.sql("""
                    INSERT INTO api_budget(day, count) VALUES (:day, 1)
                    ON CONFLICT(day) DO UPDATE SET count = api_budget.count + 1
                    WHERE api_budget.count < :stop RETURNING count
                    """).param("day", day).param("stop", properties.stopLimit()).query(Integer.class).optional();
            if (count.isEmpty()) throw new CollectionFailure(CollectionFailure.Code.BUDGET_EXHAUSTED);
            long id = jdbc.sql("""
                    INSERT INTO api_attempt(day, kind, attempt_no, started_at) VALUES (:day, :kind, :attempt, :at) RETURNING id
                    """).param("day", day).param("kind", kind)
                    .param("attempt",attempt).param("at", OffsetDateTime.now(clock)).query(Long.class).single();
            if (count.get() == properties.warningLimit()) log.warn("API_BUDGET_WARNING");
            return id;
        });
    }
    public void finish(long id, String outcome) {
        independent.executeWithoutResult(status -> jdbc.sql("""
                UPDATE api_attempt SET finished_at=:at, outcome=:outcome WHERE id=:id
                """).param("at", OffsetDateTime.now(clock)).param("outcome", outcome).param("id", id).update());
    }
}
