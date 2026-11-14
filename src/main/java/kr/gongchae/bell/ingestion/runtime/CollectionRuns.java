package kr.gongchae.bell.ingestion.runtime;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class CollectionRuns {
    private final JdbcClient jdbc;
    private final Clock clock;
    private final TransactionTemplate independent;
    public CollectionRuns(JdbcClient jdbc, Clock clock, PlatformTransactionManager manager) {
        this.jdbc = jdbc; this.clock = clock;
        independent = new TransactionTemplate(manager);
        independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    public Optional<Long> claim(String kind, String slot) {
        return independent.execute(status -> jdbc.sql("""
                INSERT INTO collect_run(kind, slot, started_at) VALUES (:kind,:slot,:at)
                ON CONFLICT(kind,slot) DO NOTHING RETURNING id
                """).param("kind",kind).param("slot",slot).param("at",OffsetDateTime.now(clock)).query(Long.class).optional());
    }
    public void metadata(long id, LocalDate periodFrom, LocalDate periodTo, LocalDate rangeFrom, LocalDate rangeTo, Integer page) {
        independent.executeWithoutResult(status -> jdbc.sql("""
                UPDATE collect_run SET period_from=:pf, period_to=:pt, range_from=:rf, range_to=:rt, page_no=:page WHERE id=:id
                """).param("pf",periodFrom).param("pt",periodTo).param("rf",rangeFrom).param("rt",rangeTo)
                .param("page",page).param("id",id).update());
    }
    public void fail(long id, CollectionFailure.Code code) {
        finish(id, "FAILED", code.name(), null, null, null);
    }
    public void finish(long id, String result, String error, Integer total, Integer received, Integer unique) {
        independent.executeWithoutResult(status -> jdbc.sql("""
                UPDATE collect_run SET finished_at=:at, result=:result, error=:error,
                  total_count=COALESCE(:total,total_count), received=COALESCE(:received,received),
                  unique_count=COALESCE(:unique,unique_count) WHERE id=:id AND result IS NULL
                """).param("at",OffsetDateTime.now(clock)).param("result",result).param("error",error)
                .param("total",total).param("received",received).param("unique",unique).param("id",id).update());
    }
    public Optional<Page> completedPage(LocalDate from, LocalDate to, int page) {
        var row = jdbc.sql("""
                SELECT id, total_count, received FROM collect_run WHERE kind='BACKFILL' AND range_from=:f
                AND range_to=:t AND page_no=:p AND result='COMPLETE' ORDER BY id DESC LIMIT 1
                """).param("f",from).param("t",to).param("p",page)
                .query((rs,n)->new long[]{rs.getLong(1),rs.getInt(2),rs.getInt(3)}).optional();
        return row.map(r -> new Page((int)r[1],(int)r[2],Set.copyOf(jdbc.sql("SELECT jsonb_array_elements_text(received_sns)::bigint FROM collect_run WHERE id=:id")
                .param("id",r[0]).query(Long.class).list())));
    }
    public Optional<Month> completedMonthSummary(LocalDate from, LocalDate to) {
        return jdbc.sql("""
                SELECT total_count,received,unique_count FROM collect_run WHERE kind='BACKFILL' AND range_from=:f AND range_to=:t
                AND page_no IS NULL AND result='COMPLETE' ORDER BY id DESC LIMIT 1
                """).param("f",from).param("t",to).query((rs,n)->new Month(rs.getInt(1),rs.getInt(2),rs.getInt(3))).optional();
    }
    public Set<Long> completedMonthSns(LocalDate from,LocalDate to) {
        return Set.copyOf(jdbc.sql("""
                SELECT DISTINCT jsonb_array_elements_text(received_sns)::bigint FROM (
                  SELECT DISTINCT ON (page_no) received_sns FROM collect_run WHERE kind='BACKFILL'
                    AND range_from=:f AND range_to=:t AND page_no IS NOT NULL AND result='COMPLETE'
                  ORDER BY page_no,id DESC
                ) pages
                """).param("f",from).param("t",to).query(Long.class).list());
    }
    public boolean withinRange(Set<Long> sns,LocalDate from,LocalDate to) {
        if(sns.isEmpty()) return true;
        return jdbc.sql("SELECT count(*) FROM posting WHERE sn IN (:sns) AND bgng_date BETWEEN :f AND :t")
                .param("sns",sns).param("f",from).param("t",to).query(Integer.class).single()==sns.size();
    }
    public void completePage(long id,int total,int received,int inserted,int changed,int closed,Set<Long> sns) {
        independent.executeWithoutResult(status -> jdbc.sql("""
                UPDATE collect_run SET result='COMPLETE', finished_at=:at, total_count=:total, received=:received,
                  inserted=:inserted, changed=:changed, closed=:closed, unique_count=:unique, received_sns=CAST(:sns AS jsonb)
                WHERE id=:id AND result IS NULL
                """).param("at",OffsetDateTime.now(clock)).param("total",total).param("received",received)
                .param("inserted",inserted).param("changed",changed).param("closed",closed)
                .param("unique",sns.size()).param("sns",sns.stream().sorted().toList().toString()).param("id",id).update());
    }
    public record Page(int total, int received,Set<Long> sns) {}
    public record Month(int total,int received,int unique) {}

}
