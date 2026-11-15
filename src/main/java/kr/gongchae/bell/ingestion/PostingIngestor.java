package kr.gongchae.bell.ingestion;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import kr.gongchae.bell.ingestion.ListResponseParser.Page;
import kr.gongchae.bell.ingestion.ListResponseParser.PostingRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.Assert;

/**
 * Applies list responses to the posting tables. Contract: collab/2026-11-15-implementation/claude-ingestion-contract.md.
 * HTTP, budget, slot claiming and scheduling belong to the caller.
 */
@Service
public class PostingIngestor {
    private static final Logger log = LoggerFactory.getLogger(PostingIngestor.class);
    /** PRD FR-C4 compared fields, in hash order. */
    static final List<String> COMPARED = List.of("title", "bgng_date", "end_date", "recrut_se", "hire", "ncs", "region",
            "recrut_nope_raw", "src_url", "aply_qlfc", "pref", "scrnprcdr");
    /** Suspected broken text, not a confirmed one: U+FFFD, or '?' glued to a Hangul syllable (observed where 「」 were lost). */
    private static final Pattern BROKEN = Pattern.compile("�|\\?[가-힣]");

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final ZoneId zone;
    private final ListResponseParser parser = new ListResponseParser();

    public PostingIngestor(JdbcClient jdbc, PlatformTransactionManager transactionManager, Clock clock) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
        this.zone = clock.getZone();
    }

    public RunResult applyRegular(long runId, String body, Instant observedAt) {
        requireNoTransaction();
        requireOpenRun(runId);
        Page page;
        try {
            page = parser.parse(body);
        } catch (IngestionException e) {
            return fail(runId, e, null);
        }
        Counts received = new Counts(page.totalCount(), page.rows().size(), 0, 0, 0);
        if (page.rows().isEmpty()) {
            return fail(runId, new IngestionException(Failure.EMPTY_SNAPSHOT, null), received);
        }
        if (page.totalCount() != page.rows().size()) {
            return fail(runId, new IngestionException(Failure.COUNT_MISMATCH,
                    "totalCount=" + page.totalCount() + " received=" + page.rows().size()), received);
        }
        try {
            Counts counts = tx.execute(status -> {
                Counts c = write(page, false, true, observedAt);
                finish(runId, "COMPLETE", null, c);
                return c;
            });
            return new RunResult("COMPLETE", null, counts);
        } catch (RuntimeException e) {
            try {
                tx.executeWithoutResult(status -> finish(runId, Failure.APPLY_ERROR.result(), Failure.APPLY_ERROR, received));
            } catch (RuntimeException recordFailure) {
                e.addSuppressed(recordFailure);
            }
            throw e;
        }
    }

    /** Parses and validates a backfill page without touching the database. Throws {@link IngestionException}. */
    public ParsedPage parsePage(String body) {
        return new ParsedPage(parser.parse(body));
    }

    /** Applies a page the caller has accepted, in one transaction. Absent postings are not judged. */
    public Counts upsertPage(ParsedPage page, Instant observedAt) {
        requireNoTransaction();
        return tx.execute(status -> write(page.page(), true, false, observedAt));
    }

    private RunResult fail(long runId, IngestionException e, Counts counts) {
        tx.executeWithoutResult(status -> finish(runId, e.failure().result(), e.failure(), counts));
        log.warn("Regular collection run {} not applied: {}", runId, e.getMessage());
        return new RunResult(e.failure().result(), e.failure(), counts);
    }

    private Counts write(Page page, boolean fromBackfill, boolean snapshot, Instant observedAt) {
        List<PostingRow> rows = page.rows().stream().sorted(Comparator.comparingLong(PostingRow::sn)).toList();
        if (rows.isEmpty()) {
            return new Counts(page.totalCount(), 0, 0, 0, 0);
        }
        LocalDate today = observedAt.atZone(zone).toLocalDate();
        OffsetDateTime at = observedAt.atOffset(ZoneOffset.UTC);
        // Lock order everywhere: institution codes, code names, then posting sn ascending (no deadlocks between applies).
        upsertInstitutions(rows, at);
        upsertCodeNames(rows);
        List<Long> sns = rows.stream().map(PostingRow::sn).toList();
        Map<Long, Stored> stored = lock(sns, snapshot);
        Map<Long, Map<String, List<String>>> storedCodes = loadCodes(stored.keySet());
        Map<String, Long> urlUse = new HashMap<>();
        for (PostingRow row : rows) {
            if (isHttpUrl(row.srcUrl())) {
                urlUse.merge(row.instCode() + "\n" + row.srcUrl(), 1L, Long::sum);
            }
        }
        int inserted = 0, changed = 0, closed = 0;
        for (PostingRow row : rows) {
            String status = row.end().isBefore(today) || "N".equals(row.ongoingYn()) ? "CLOSED" : "OPEN";
            boolean shared = urlUse.getOrDefault(row.instCode() + "\n" + row.srcUrl(), 0L) > 1;
            boolean generic = isHttpUrl(row.srcUrl()) && (isRootUrl(row.srcUrl()) || shared);
            List<Object> values = compared(row);
            String hash = sha256(values);
            Stored old = stored.get(row.sn());
            Map<String, List<String>> oldCodes = storedCodes.getOrDefault(row.sn(), Map.of());
            if (old == null) {
                if (insert(row, generic, status, fromBackfill, hash, at)) {
                    insertCodes(row);
                    inserted++;
                    continue;
                }
                // Another transaction committed this sn after our lock query: treat it as an existing row.
                old = lock(List.of(row.sn()), false).get(row.sn());
                oldCodes = loadCodes(Set.of(row.sn())).getOrDefault(row.sn(), Map.of());
            }
            generic = generic || (old.srcUrlGeneric() && Objects.equals(old.srcUrl(), row.srcUrl()));
            Map<String, Map<String, Object>> diff = hash.equals(old.contentHash()) ? Map.of() : diff(old, oldCodes, values);
            update(row, generic, status, hash, at, !diff.isEmpty());
            if (!row.codes().equals(oldCodes)) {
                jdbc.sql("DELETE FROM posting_code WHERE sn = :sn").param("sn", row.sn()).update();
                insertCodes(row);
            }
            if (!diff.isEmpty()) {
                jdbc.sql("INSERT INTO posting_change(sn, observed_at, changed_fields) VALUES (:sn, :at, CAST(:fields AS jsonb))")
                        .param("sn", row.sn()).param("at", at)
                        .param("fields", ListResponseParser.JSON.writeValueAsString(diff)).update();
                changed++;
            }
            if (status.equals("CLOSED") && !old.status().equals("CLOSED")) {
                closed++;
            }
        }
        if (snapshot) {
            closed += markMissing(stored, Set.copyOf(sns), today);
        }
        return new Counts(page.totalCount(), rows.size(), inserted, changed, closed);
    }

    /** Complete snapshot only: OPEN/CHECK postings absent from the response are CLOSED after their deadline, else CHECK. */
    private int markMissing(Map<Long, Stored> stored, Set<Long> present, LocalDate today) {
        int closed = 0;
        for (Stored s : stored.values()) {
            if (present.contains(s.sn()) || s.status().equals("CLOSED")) {
                continue;
            }
            String status = s.endDate().isBefore(today) ? "CLOSED" : "CHECK";
            if (!status.equals(s.status())) {
                jdbc.sql("UPDATE posting SET status = :status WHERE sn = :sn").param("status", status).param("sn", s.sn()).update();
                closed += status.equals("CLOSED") ? 1 : 0;
            }
        }
        return closed;
    }

    private record Stored(long sn, String title, LocalDate bgngDate, LocalDate endDate, String recrutSe, String nopeRaw,
                          String aplyQlfc, String pref, String scrnprcdr, String srcUrl, boolean srcUrlGeneric,
                          String status, String contentHash) {
    }

    private Map<Long, Stored> lock(Collection<Long> sns, boolean withOpen) {
        var result = new TreeMap<Long, Stored>();
        jdbc.sql("SELECT sn, title, bgng_date, end_date, recrut_se, recrut_nope_raw, aply_qlfc, pref, scrnprcdr,"
                        + " src_url, src_url_generic, status, content_hash FROM posting WHERE sn IN (:sns)"
                        + (withOpen ? " OR status IN ('OPEN', 'CHECK')" : "") + " ORDER BY sn FOR UPDATE")
                .param("sns", sns)
                .query((rs, i) -> new Stored(rs.getLong("sn"), rs.getString("title"), rs.getObject("bgng_date", LocalDate.class),
                        rs.getObject("end_date", LocalDate.class), rs.getString("recrut_se"), rs.getString("recrut_nope_raw"),
                        rs.getString("aply_qlfc"), rs.getString("pref"), rs.getString("scrnprcdr"), rs.getString("src_url"),
                        rs.getBoolean("src_url_generic"), rs.getString("status"), rs.getString("content_hash")))
                .list().forEach(s -> result.put(s.sn(), s));
        return result;
    }

    private Map<Long, Map<String, List<String>>> loadCodes(Collection<Long> sns) {
        var result = new HashMap<Long, Map<String, List<String>>>();
        if (sns.isEmpty()) {
            return result;
        }
        jdbc.sql("SELECT sn, type, code FROM posting_code WHERE sn IN (:sns) ORDER BY sn, type, code")
                .param("sns", sns)
                .query(rs -> {
                    result.computeIfAbsent(rs.getLong("sn"), k -> new TreeMap<>())
                            .computeIfAbsent(rs.getString("type"), k -> new ArrayList<>()).add(rs.getString("code"));
                });
        return result;
    }

    private boolean insert(PostingRow row, boolean generic, String status, boolean fromBackfill, String hash, OffsetDateTime at) {
        return bind(jdbc.sql("""
                INSERT INTO posting(sn, inst_code, title, bgng_date, end_date, recrut_se, recrut_nope_raw, recrut_nope,
                    aply_qlfc, pref, scrnprcdr, src_url, src_url_generic, broken_text, status, ongoing_yn, from_backfill,
                    content_hash, first_seen_at, last_seen_at)
                VALUES (:sn, :inst, :title, :begin, :end, :recrutSe, :nopeRaw, :nope, :aplyQlfc, :pref, :scrnprcdr, :srcUrl,
                    :generic, :broken, :status, :ongoing, :fromBackfill, :hash, :at, :at)
                ON CONFLICT (sn) DO NOTHING"""), row, generic, status, hash, at)
                .param("fromBackfill", fromBackfill).update() == 1;
    }

    private void update(PostingRow row, boolean generic, String status, String hash, OffsetDateTime at, boolean changed) {
        bind(jdbc.sql("""
                UPDATE posting SET inst_code = :inst, title = :title, bgng_date = :begin, end_date = :end, recrut_se = :recrutSe,
                    recrut_nope_raw = :nopeRaw, recrut_nope = :nope, aply_qlfc = :aplyQlfc, pref = :pref, scrnprcdr = :scrnprcdr,
                    src_url = :srcUrl, src_url_generic = :generic, broken_text = :broken, status = :status, ongoing_yn = :ongoing,
                    content_hash = :hash, last_seen_at = :at, last_changed_at = COALESCE(:changedAt, last_changed_at)
                WHERE sn = :sn"""), row, generic, status, hash, at)
                .param("changedAt", changed ? at : null, java.sql.Types.TIMESTAMP_WITH_TIMEZONE).update();
    }

    private static JdbcClient.StatementSpec bind(JdbcClient.StatementSpec spec, PostingRow row, boolean generic, String status,
                                                 String hash, OffsetDateTime at) {
        boolean broken = Arrays.asList(row.title(), row.instName(), row.aplyQlfc(), row.pref(), row.scrnprcdr()).stream()
                .anyMatch(text -> text != null && BROKEN.matcher(text).find());
        return spec.param("sn", row.sn()).param("inst", row.instCode()).param("title", row.title())
                .param("begin", row.begin()).param("end", row.end())
                .param("recrutSe", row.recrutSe(), java.sql.Types.VARCHAR).param("nopeRaw", row.nopeRaw(), java.sql.Types.VARCHAR)
                .param("nope", row.nope(), java.sql.Types.BIGINT).param("aplyQlfc", row.aplyQlfc(), java.sql.Types.VARCHAR)
                .param("pref", row.pref(), java.sql.Types.VARCHAR).param("scrnprcdr", row.scrnprcdr(), java.sql.Types.VARCHAR)
                .param("srcUrl", row.srcUrl(), java.sql.Types.VARCHAR).param("generic", generic).param("broken", broken)
                .param("status", status).param("ongoing", row.ongoingYn(), java.sql.Types.VARCHAR)
                .param("hash", hash).param("at", at);
    }

    private void insertCodes(PostingRow row) {
        row.codes().forEach((type, codes) -> codes.forEach(code ->
                jdbc.sql("INSERT INTO posting_code(sn, type, code) VALUES (:sn, :type, :code)")
                        .param("sn", row.sn()).param("type", type).param("code", code).update()));
    }

    private void upsertInstitutions(List<PostingRow> rows, OffsetDateTime at) {
        var names = new TreeMap<String, String>();
        rows.forEach(row -> names.put(row.instCode(), row.instName()));
        names.forEach((code, name) -> jdbc.sql("""
                INSERT INTO institution(code, name, updated_at) VALUES (:code, :name, :at)
                ON CONFLICT (code) DO UPDATE SET name = EXCLUDED.name, updated_at = EXCLUDED.updated_at
                WHERE institution.name IS DISTINCT FROM EXCLUDED.name""")
                .param("code", code).param("name", name).param("at", at).update());
    }

    private void upsertCodeNames(List<PostingRow> rows) {
        var names = new TreeMap<String, String>();
        rows.forEach(row -> row.names().forEach((type, byCode) ->
                byCode.forEach((code, name) -> names.put(type + "\n" + code, name))));
        names.forEach((key, name) -> {
            String[] typeCode = key.split("\n", 2);
            jdbc.sql("""
                    INSERT INTO code_name(type, code, name) VALUES (:type, :code, :name)
                    ON CONFLICT (type, code) DO UPDATE SET name = EXCLUDED.name WHERE code_name.name IS DISTINCT FROM EXCLUDED.name""")
                    .param("type", typeCode[0]).param("code", typeCode[1]).param("name", name).update();
        });
    }

    private void finish(long runId, String result, Failure failure, Counts counts) {
        int updated = jdbc.sql("""
                UPDATE collect_run SET finished_at = now(), result = :result, error = :error, total_count = :total,
                    received = :received, inserted = :inserted, changed = :changed, closed = :closed
                WHERE id = :id AND result IS NULL""")
                .param("id", runId).param("result", result)
                .param("error", failure == null ? null : failure.name(), java.sql.Types.VARCHAR)
                .param("total", counts == null ? null : counts.totalCount(), java.sql.Types.INTEGER)
                .param("received", counts == null ? null : counts.received(), java.sql.Types.INTEGER)
                .param("inserted", counts == null ? null : counts.inserted(), java.sql.Types.INTEGER)
                .param("changed", counts == null ? null : counts.changed(), java.sql.Types.INTEGER)
                .param("closed", counts == null ? null : counts.closed(), java.sql.Types.INTEGER)
                .update();
        Assert.state(updated == 1, () -> "collect_run " + runId + " is missing or already finished");
    }

    private void requireOpenRun(long runId) {
        boolean open = jdbc.sql("SELECT count(*) FROM collect_run WHERE id = :id AND result IS NULL")
                .param("id", runId).query(Long.class).single() == 1;
        Assert.state(open, () -> "collect_run " + runId + " is missing or already finished");
    }

    private static void requireNoTransaction() {
        Assert.state(!TransactionSynchronizationManager.isActualTransactionActive(),
                "PostingIngestor opens its own transactions; call it outside a transaction");
    }

    private static List<Object> compared(PostingRow row) {
        return Arrays.asList(row.title(), row.begin().toString(), row.end().toString(), row.recrutSe(),
                row.codes().getOrDefault("HIRE", List.of()), row.codes().getOrDefault("NCS", List.of()),
                row.codes().getOrDefault("REGION", List.of()), row.nopeRaw(), row.srcUrl(), row.aplyQlfc(), row.pref(),
                row.scrnprcdr());
    }

    private static Map<String, Map<String, Object>> diff(Stored old, Map<String, List<String>> oldCodes, List<Object> values) {
        List<Object> before = Arrays.asList(old.title(), old.bgngDate().toString(), old.endDate().toString(), old.recrutSe(),
                oldCodes.getOrDefault("HIRE", List.of()), oldCodes.getOrDefault("NCS", List.of()),
                oldCodes.getOrDefault("REGION", List.of()), old.nopeRaw(), old.srcUrl(), old.aplyQlfc(), old.pref(),
                old.scrnprcdr());
        var diff = new LinkedHashMap<String, Map<String, Object>>();
        for (int i = 0; i < COMPARED.size(); i++) {
            if (!Objects.equals(before.get(i), values.get(i))) {
                var change = new LinkedHashMap<String, Object>();
                change.put("old", before.get(i));
                change.put("new", values.get(i));
                diff.put(COMPARED.get(i), change);
            }
        }
        return diff;
    }

    private static String sha256(List<Object> values) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(ListResponseParser.JSON.writeValueAsString(values).getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean isHttpUrl(String url) {
        URI uri = uri(url);
        return uri != null && uri.getHost() != null
                && ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()));
    }

    /** Site front page: no path beyond "/" and no query. Judged on the parsed form; the stored URL is never rewritten. */
    private static boolean isRootUrl(String url) {
        URI uri = uri(url);
        return uri != null && (uri.getRawPath() == null || uri.getRawPath().isEmpty() || uri.getRawPath().equals("/"))
                && uri.getRawQuery() == null;
    }

    private static URI uri(String url) {
        if (url == null) {
            return null;
        }
        try {
            return new URI(url);
        } catch (URISyntaxException e) {
            return null;
        }
    }
}
