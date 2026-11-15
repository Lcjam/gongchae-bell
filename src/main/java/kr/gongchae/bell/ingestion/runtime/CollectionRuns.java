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
    /** Every backfill row is START_SCAN; generation is the scan whose pages this row belongs to (null on command rows). */
    public void metadata(long id, LocalDate periodFrom, LocalDate periodTo, LocalDate rangeFrom, LocalDate rangeTo, Integer page, Long generation) {
        independent.executeWithoutResult(status -> jdbc.sql("""
                UPDATE collect_run SET period_from=:pf, period_to=:pt, range_from=:rf, range_to=:rt, page_no=:page,
                  strategy='START_SCAN', scan_generation=:generation WHERE id=:id
                """).param("pf",periodFrom).param("pt",periodTo).param("rf",rangeFrom).param("rt",rangeTo)
                .param("page",page).param("generation",generation).param("id",id).update());
    }
    public void fail(long id, CollectionFailure.Code code) {
        finish(id, "FAILED", code.name(), null, null, null);
    }
    public void finish(long id, String result, String error, Integer total, Integer received, Integer unique) {
        finish(id, result, error, total, received, unique, null);
    }
    public void finish(long id, String result, String error, Integer total, Integer received, Integer unique, Integer applied) {
        independent.executeWithoutResult(status -> jdbc.sql("""
                UPDATE collect_run SET finished_at=:at, result=:result, error=:error,
                  total_count=COALESCE(:total,total_count), received=COALESCE(:received,received),
                  unique_count=COALESCE(:unique,unique_count), applied_count=COALESCE(:applied,applied_count)
                WHERE id=:id AND result IS NULL
                """).param("at",OffsetDateTime.now(clock)).param("result",result).param("error",error)
                .param("total",total).param("received",received).param("unique",unique).param("applied",applied)
                .param("id",id).update());
    }
    /** Page {@code page} completed in this scan generation; never a page of another generation or a pre-V4 monthly row. */
    public Optional<Page> completedPage(long generation, int page) {
        var row = jdbc.sql("""
                SELECT id, total_count, received FROM collect_run WHERE kind='BACKFILL' AND strategy='START_SCAN'
                AND scan_generation=:generation AND page_no=:p AND result='COMPLETE' ORDER BY id DESC LIMIT 1
                """).param("generation",generation).param("p",page)
                .query((rs,n)->new long[]{rs.getLong(1),rs.getInt(2),rs.getInt(3)}).optional();
        return row.map(r -> new Page((int)r[1],(int)r[2],sns(r[0],"received_sns"),sns(r[0],"applied_sns")));
    }
    /** The newest scan row of exactly this period, whatever its result; only a COMPLETE one carries its own two SN sets. */
    public Optional<Scan> latestScan(LocalDate from, LocalDate to) {
        var row = jdbc.sql("""
                SELECT id, scan_generation, result='COMPLETE', total_count, received, unique_count, applied_count FROM collect_run
                WHERE kind='BACKFILL' AND strategy='START_SCAN' AND range_from=:f AND range_to=:t AND page_no IS NULL
                ORDER BY id DESC LIMIT 1
                """).param("f",from).param("t",to)
                .query((rs,n)->new long[]{rs.getLong(1),rs.getLong(2),rs.getBoolean(3)?1:0,rs.getInt(4),rs.getInt(5),rs.getInt(6),rs.getInt(7)})
                .optional();
        return row.map(r -> r[2]==0 ? new Scan(r[1],null,0,0)
                : new Scan(r[1],new Page((int)r[3],(int)r[4],sns(r[0],"received_sns"),sns(r[0],"applied_sns")),(int)r[5],(int)r[6]));
    }
    private Set<Long> sns(long id, String column) {
        // column is one of the two literal names above, never input.
        return Set.copyOf(jdbc.sql("SELECT jsonb_array_elements_text(" + column + ")::bigint FROM collect_run WHERE id=:id")
                .param("id",id).query(Long.class).list());
    }
    public void completePage(long id, Page page, int inserted, int changed, int closed) {
        complete(id,page,inserted,changed,closed);
    }
    public void completeScan(long id, Page scan) {
        complete(id,scan,null,null,null);
    }
    private void complete(long id, Page page, Integer inserted, Integer changed, Integer closed) {
        independent.executeWithoutResult(status -> jdbc.sql("""
                UPDATE collect_run SET result='COMPLETE', finished_at=:at, total_count=:total, received=:received,
                  inserted=:inserted, changed=:changed, closed=:closed, unique_count=:unique, received_sns=CAST(:sns AS jsonb),
                  applied_count=:applied, applied_sns=CAST(:appliedSns AS jsonb)
                WHERE id=:id AND result IS NULL
                """).param("at",OffsetDateTime.now(clock)).param("total",page.total()).param("received",page.received())
                .param("inserted",inserted).param("changed",changed).param("closed",closed)
                .param("unique",page.sns().size()).param("sns",json(page.sns()))
                .param("applied",page.applied().size()).param("appliedSns",json(page.applied())).param("id",id).update());
    }
    private static String json(Set<Long> sns) {
        return sns.stream().sorted().toList().toString();
    }
    /** Upstream (raw) totals and SNs, plus the SNs of rows that started within the period and were applied. */
    public record Page(int total, int received, Set<Long> sns, Set<Long> applied) {}
    /** completed is null unless the scan finished; unique and applied are the counts stored next to its own sets. */
    public record Scan(long generation, Page completed, int unique, int applied) {}

}
