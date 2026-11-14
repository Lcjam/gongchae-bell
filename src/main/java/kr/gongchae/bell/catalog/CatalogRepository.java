package kr.gongchae.bell.catalog;

import static kr.gongchae.bell.catalog.CatalogViews.FileItem;
import static kr.gongchae.bell.catalog.CatalogViews.FilterOption;
import static kr.gongchae.bell.catalog.CatalogViews.InstitutionDetailView;
import static kr.gongchae.bell.catalog.CatalogViews.InstitutionHistoryPage;
import static kr.gongchae.bell.catalog.CatalogViews.InstitutionSummary;
import static kr.gongchae.bell.catalog.CatalogViews.JobSearchResult;
import static kr.gongchae.bell.catalog.CatalogViews.MonthlyPatternRow;
import static kr.gongchae.bell.catalog.CatalogViews.NcsDistributionRow;
import static kr.gongchae.bell.catalog.CatalogViews.PostingDetail;
import static kr.gongchae.bell.catalog.CatalogViews.PostingSource;
import static kr.gongchae.bell.catalog.CatalogViews.PostingSummary;
import static kr.gongchae.bell.catalog.CatalogViews.StepItem;
import static kr.gongchae.bell.catalog.CatalogViews.YearlyRecruitStat;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Repository
public class CatalogRepository {
    public static final int HOME_CLOSING_LIMIT = 12;
    public static final int HOME_TODAY_LIMIT = 20;
    public static final int INSTITUTION_HISTORY_PAGE_SIZE = 20;

    private static final Pattern DIGITS = Pattern.compile("^\\d+$");

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public CatalogRepository(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public Map<String, List<FilterOption>> findFilterOptions() {
        Map<String, List<FilterOption>> grouped = new LinkedHashMap<>();
        grouped.put("NCS", new ArrayList<>());
        grouped.put("REGION", new ArrayList<>());
        grouped.put("HIRE", new ArrayList<>());

        jdbc.sql("""
                        SELECT type, code, name
                        FROM code_name
                        WHERE type IN ('NCS', 'REGION', 'HIRE')
                        ORDER BY type ASC, name ASC, code ASC
                        """)
                .query(rs -> {
                    String type = rs.getString("type");
                    grouped.computeIfAbsent(type, k -> new ArrayList<>())
                            .add(new FilterOption(rs.getString("code"), rs.getString("name")));
                });
        return grouped;
    }

    public Map<String, Set<String>> allowedCodeSet(Map<String, List<FilterOption>> options) {
        Map<String, Set<String>> allowed = new HashMap<>();
        for (var entry : options.entrySet()) {
            Set<String> codes = new LinkedHashSet<>();
            for (FilterOption option : entry.getValue()) {
                codes.add(option.code());
            }
            allowed.put(entry.getKey(), codes);
        }
        return allowed;
    }

    public List<PostingSummary> findClosingSoon(LocalDate kstToday) {
        List<SummaryRow> rows = jdbc.sql("""
                        SELECT p.sn, p.inst_code, i.name AS inst_name, p.title,
                               p.bgng_date, p.end_date, p.recrut_se, p.status, p.broken_text
                        FROM posting p
                        JOIN institution i ON i.code = p.inst_code
                        WHERE p.status = 'OPEN'
                          AND p.end_date BETWEEN :today AND :plus3
                        ORDER BY p.end_date ASC, p.sn ASC
                        LIMIT :limit
                        """)
                .param("today", kstToday)
                .param("plus3", kstToday.plusDays(3))
                .param("limit", HOME_CLOSING_LIMIT)
                .query(this::mapSummaryRow)
                .list();
        return attachCodes(rows, kstToday);
    }

    public List<PostingSummary> findStartedToday(LocalDate kstToday) {
        List<SummaryRow> rows = jdbc.sql("""
                        SELECT p.sn, p.inst_code, i.name AS inst_name, p.title,
                               p.bgng_date, p.end_date, p.recrut_se, p.status, p.broken_text
                        FROM posting p
                        JOIN institution i ON i.code = p.inst_code
                        WHERE p.bgng_date = :today
                        ORDER BY p.end_date ASC, p.sn ASC
                        LIMIT :limit
                        """)
                .param("today", kstToday)
                .param("limit", HOME_TODAY_LIMIT)
                .query(this::mapSummaryRow)
                .list();
        return attachCodes(rows, kstToday);
    }

    public JobSearchResult searchJobs(JobSearchCriteria criteria, LocalDate kstToday) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("today", kstToday);

        if (criteria.ncs() != null) {
            where.append(" AND EXISTS (SELECT 1 FROM posting_code pc WHERE pc.sn = p.sn AND pc.type = 'NCS' AND pc.code = :ncs)");
            params.put("ncs", criteria.ncs());
        }
        if (criteria.region() != null) {
            where.append(" AND EXISTS (SELECT 1 FROM posting_code pc WHERE pc.sn = p.sn AND pc.type = 'REGION' AND pc.code = :region)");
            params.put("region", criteria.region());
        }
        if (criteria.hire() != null) {
            where.append(" AND EXISTS (SELECT 1 FROM posting_code pc WHERE pc.sn = p.sn AND pc.type = 'HIRE' AND pc.code = :hire)");
            params.put("hire", criteria.hire());
        }
        if (criteria.career() != null) {
            switch (criteria.career()) {
                case "newcomer" -> where.append(" AND p.recrut_se IN ('R2010', 'R2030')");
                case "newonly" -> where.append(" AND p.recrut_se = 'R2010'");
                case "career" -> where.append(" AND p.recrut_se = 'R2020'");
                default -> { }
            }
        }
        switch (criteria.status()) {
            case "open" -> where.append(" AND p.status IN ('OPEN', 'CHECK') AND p.end_date >= :today");
            case "check" -> where.append(" AND p.status = 'CHECK' AND p.end_date >= :today");
            case "closed" -> where.append(" AND (p.status = 'CLOSED' OR p.end_date < :today)");
            case "all" -> { }
            default -> where.append(" AND p.status IN ('OPEN', 'CHECK') AND p.end_date >= :today");
        }
        if (criteria.q() != null) {
            where.append(" AND p.title ILIKE :likePattern ESCAPE '\\'");
            params.put("likePattern", criteria.likePattern());
        }

        long totalCount = jdbc.sql("SELECT COUNT(*) FROM posting p" + where)
                .params(params)
                .query(Long.class)
                .single();

        int pageSize = JobSearchCriteria.PAGE_SIZE;
        int totalPages = totalCount == 0 ? 0 : (int) ((totalCount + pageSize - 1) / pageSize);
        int effectivePage = totalPages == 0 ? 1 : Math.min(criteria.page(), totalPages);
        int offset = (effectivePage - 1) * pageSize;

        String orderBy = "begin".equals(criteria.sort())
                ? " ORDER BY p.bgng_date DESC, p.sn DESC"
                : " ORDER BY p.end_date ASC, p.sn ASC";

        Map<String, Object> pageParams = new LinkedHashMap<>(params);
        pageParams.put("limit", pageSize);
        pageParams.put("offset", offset);

        List<SummaryRow> rows = jdbc.sql("""
                        SELECT p.sn, p.inst_code, i.name AS inst_name, p.title,
                               p.bgng_date, p.end_date, p.recrut_se, p.status, p.broken_text
                        FROM posting p
                        JOIN institution i ON i.code = p.inst_code
                        """ + where + orderBy + " LIMIT :limit OFFSET :offset")
                .params(pageParams)
                .query(this::mapSummaryRow)
                .list();

        return new JobSearchResult(attachCodes(rows, kstToday), totalCount, effectivePage, pageSize, totalPages);
    }

    public boolean hasAnyPosting() {
        return Boolean.TRUE.equals(jdbc.sql("SELECT EXISTS (SELECT 1 FROM posting)")
                .query(Boolean.class)
                .single());
    }

    public Optional<PostingDetail> findJobDetail(long sn, LocalDate kstToday) {
        Optional<DetailRow> opt = jdbc.sql("""
                        SELECT p.sn, p.inst_code, i.name AS inst_name, p.title,
                               p.bgng_date, p.end_date, p.recrut_se, p.recrut_nope_raw, p.recrut_nope,
                               p.aply_qlfc, p.pref, p.scrnprcdr, p.src_url, p.src_url_generic,
                               p.broken_text, p.status, p.detail_status,
                               p.steps::text AS steps_json, p.files::text AS files_json
                        FROM posting p
                        JOIN institution i ON i.code = p.inst_code
                        WHERE p.sn = :sn
                        """)
                .param("sn", sn)
                .query((rs, rowNum) -> new DetailRow(
                        rs.getLong("sn"),
                        rs.getString("inst_code"),
                        rs.getString("inst_name"),
                        rs.getString("title"),
                        rs.getObject("bgng_date", LocalDate.class),
                        rs.getObject("end_date", LocalDate.class),
                        rs.getString("recrut_se"),
                        rs.getString("recrut_nope_raw"),
                        rs.getObject("recrut_nope", Long.class),
                        rs.getString("aply_qlfc"),
                        rs.getString("pref"),
                        rs.getString("scrnprcdr"),
                        rs.getString("src_url"),
                        rs.getBoolean("src_url_generic"),
                        rs.getBoolean("broken_text"),
                        rs.getString("status"),
                        rs.getString("detail_status"),
                        rs.getString("steps_json"),
                        rs.getString("files_json")
                ))
                .optional();

        if (opt.isEmpty()) {
            return Optional.empty();
        }
        DetailRow row = opt.get();
        Map<String, List<String>> codes = loadCodesBySn(List.of(sn))
                .getOrDefault(sn, Map.of());

        String effectiveStatus = CatalogViews.effectiveStatus(row.status(), row.endDate(), kstToday);
        String statusLabel = CatalogViews.statusLabel(effectiveStatus);
        String ddayLabel = CatalogViews.ddayLabel(effectiveStatus, row.endDate(), kstToday);
        String recruitDisplay = formatRecruitDisplay(row.recrutNopeRaw(), row.recrutNope());

        ParsedSteps parsedSteps = "DONE".equals(row.detailStatus())
                ? parseSteps(row.stepsJson())
                : new ParsedSteps(List.of(), false);
        ParsedFiles parsedFiles = "DONE".equals(row.detailStatus())
                ? parseFiles(row.filesJson())
                : new ParsedFiles(List.of(), false);

        return Optional.of(new PostingDetail(
                row.sn(),
                row.instCode(),
                row.instName(),
                row.title(),
                row.bgngDate(),
                row.endDate(),
                row.recrutSe(),
                CatalogViews.recrutSeLabel(row.recrutSe()),
                recruitDisplay,
                row.aplyQlfc(),
                row.pref(),
                row.scrnprcdr(),
                SafeUrlValidator.sanitizeHttpUrl(row.srcUrl()),
                row.srcUrlGeneric(),
                SafeUrlValidator.alioDetailUrl(row.sn()),
                row.brokenText(),
                effectiveStatus,
                statusLabel,
                ddayLabel,
                row.detailStatus(),
                parsedSteps.items(),
                parsedSteps.unknownFormat(),
                parsedFiles.items(),
                parsedFiles.unknownFormat(),
                codes.getOrDefault("NCS", List.of()),
                codes.getOrDefault("REGION", List.of()),
                codes.getOrDefault("HIRE", List.of()),
                codes.getOrDefault("EDU", List.of())
        ));
    }

    public Optional<PostingSource> findPostingSource(long sn) {
        return jdbc.sql("SELECT sn, src_url, src_url_generic FROM posting WHERE sn = :sn")
                .param("sn", sn)
                .query((rs, rowNum) -> new PostingSource(
                        rs.getLong("sn"),
                        rs.getString("src_url"),
                        rs.getBoolean("src_url_generic")
                ))
                .optional();
    }

    public Optional<InstitutionDetailView> findInstitutionDetail(String code, LocalDate kstToday, int requestedPage) {
        Optional<String> instNameOpt = jdbc.sql("SELECT name FROM institution WHERE code = :code")
                .param("code", code)
                .query(String.class)
                .optional();
        if (instNameOpt.isEmpty()) {
            return Optional.empty();
        }
        String instName = instNameOpt.get();
        LocalDate fromDate = kstToday.minusYears(3);

        InstitutionSummary summary = jdbc.sql("""
                        SELECT COUNT(*) AS total_postings,
                               COALESCE(SUM(recrut_nope) FILTER (WHERE recrut_nope IS NOT NULL), 0) AS total_planned,
                               COUNT(*) FILTER (WHERE recrut_nope IS NULL) AS excluded_count
                        FROM posting
                        WHERE inst_code = :code
                          AND bgng_date BETWEEN :fromDate AND :toDate
                        """)
                .param("code", code)
                .param("fromDate", fromDate)
                .param("toDate", kstToday)
                .query((rs, rowNum) -> new InstitutionSummary(
                        code,
                        instName,
                        fromDate,
                        kstToday,
                        rs.getLong("total_postings"),
                        rs.getLong("total_planned"),
                        rs.getLong("excluded_count")
                ))
                .single();

        List<YearlyRecruitStat> yearlyRecruits = jdbc.sql("""
                        SELECT EXTRACT(YEAR FROM bgng_date)::int AS yr,
                               COUNT(*) AS posting_count,
                               COALESCE(SUM(recrut_nope) FILTER (WHERE recrut_nope IS NOT NULL), 0) AS planned_recruits,
                               COUNT(*) FILTER (WHERE recrut_nope IS NULL) AS excluded_count
                        FROM posting
                        WHERE inst_code = :code
                          AND bgng_date BETWEEN :fromDate AND :toDate
                        GROUP BY yr
                        ORDER BY yr DESC
                        """)
                .param("code", code)
                .param("fromDate", fromDate)
                .param("toDate", kstToday)
                .query((rs, rowNum) -> new YearlyRecruitStat(
                        rs.getInt("yr"),
                        rs.getLong("posting_count"),
                        rs.getLong("planned_recruits"),
                        rs.getLong("excluded_count")
                ))
                .list();

        Map<Integer, int[]> monthGrid = new LinkedHashMap<>();
        for (int yr = kstToday.getYear(); yr >= fromDate.getYear(); yr--) {
            monthGrid.put(yr, new int[12]);
        }
        jdbc.sql("""
                        SELECT EXTRACT(YEAR FROM bgng_date)::int AS yr,
                               EXTRACT(MONTH FROM bgng_date)::int AS mo,
                               COUNT(*)::int AS cnt
                        FROM posting
                        WHERE inst_code = :code
                          AND bgng_date BETWEEN :fromDate AND :toDate
                        GROUP BY yr, mo
                        ORDER BY yr DESC, mo ASC
                        """)
                .param("code", code)
                .param("fromDate", fromDate)
                .param("toDate", kstToday)
                .query(rs -> {
                    int yr = rs.getInt("yr");
                    int mo = rs.getInt("mo");
                    int cnt = rs.getInt("cnt");
                    int[] counts = monthGrid.computeIfAbsent(yr, k -> new int[12]);
                    if (mo >= 1 && mo <= 12) {
                        counts[mo - 1] = cnt;
                    }
                });

        List<MonthlyPatternRow> monthlyPatterns = new ArrayList<>();
        for (var entry : monthGrid.entrySet()) {
            long yearTotal = 0;
            for (int c : entry.getValue()) {
                yearTotal += c;
            }
            monthlyPatterns.add(new MonthlyPatternRow(entry.getKey(), entry.getValue(), yearTotal));
        }

        List<NcsDistributionRow> ncsDistribution = jdbc.sql("""
                        SELECT pc.code, COALESCE(cn.name, pc.code) AS name, COUNT(DISTINCT p.sn) AS cnt
                        FROM posting p
                        JOIN posting_code pc ON pc.sn = p.sn AND pc.type = 'NCS'
                        LEFT JOIN code_name cn ON cn.type = 'NCS' AND cn.code = pc.code
                        WHERE p.inst_code = :code
                          AND p.bgng_date BETWEEN :fromDate AND :toDate
                        GROUP BY pc.code, cn.name
                        ORDER BY cnt DESC, pc.code ASC
                        """)
                .param("code", code)
                .param("fromDate", fromDate)
                .param("toDate", kstToday)
                .query((rs, rowNum) -> new NcsDistributionRow(
                        rs.getString("code"),
                        rs.getString("name"),
                        rs.getLong("cnt")
                ))
                .list();

        long totalHistoryCount = summary.totalPostings();
        int pageSize = INSTITUTION_HISTORY_PAGE_SIZE;
        int totalPages = totalHistoryCount == 0 ? 0 : (int) ((totalHistoryCount + pageSize - 1) / pageSize);
        int effectivePage = totalPages == 0 ? 1 : Math.min(Math.max(1, requestedPage), totalPages);
        int offset = (effectivePage - 1) * pageSize;

        List<SummaryRow> historyRows = jdbc.sql("""
                        SELECT p.sn, p.inst_code, i.name AS inst_name, p.title,
                               p.bgng_date, p.end_date, p.recrut_se, p.status, p.broken_text
                        FROM posting p
                        JOIN institution i ON i.code = p.inst_code
                        WHERE p.inst_code = :code
                          AND p.bgng_date BETWEEN :fromDate AND :toDate
                        ORDER BY p.bgng_date DESC, p.sn DESC
                        LIMIT :limit OFFSET :offset
                        """)
                .param("code", code)
                .param("fromDate", fromDate)
                .param("toDate", kstToday)
                .param("limit", pageSize)
                .param("offset", offset)
                .query(this::mapSummaryRow)
                .list();

        InstitutionHistoryPage historyPage = new InstitutionHistoryPage(
                attachCodes(historyRows, kstToday),
                totalHistoryCount,
                effectivePage,
                pageSize,
                totalPages
        );

        return Optional.of(new InstitutionDetailView(
                summary,
                yearlyRecruits,
                monthlyPatterns,
                ncsDistribution,
                historyPage
        ));
    }

    public List<Long> findRecentPostingSnsForSitemap(int limit) {
        return jdbc.sql("SELECT sn FROM posting ORDER BY bgng_date DESC, sn DESC LIMIT :limit")
                .param("limit", limit)
                .query(Long.class)
                .list();
    }

    public List<String> findInstitutionCodesForSitemap(int limit) {
        return jdbc.sql("SELECT code FROM institution ORDER BY code ASC LIMIT :limit")
                .param("limit", limit)
                .query(String.class)
                .list();
    }

    private List<PostingSummary> attachCodes(List<SummaryRow> rows, LocalDate kstToday) {
        if (rows.isEmpty()) {
            return List.of();
        }
        List<Long> sns = rows.stream().map(SummaryRow::sn).toList();
        Map<Long, Map<String, List<String>>> codesBySn = loadCodesBySn(sns);
        List<PostingSummary> result = new ArrayList<>(rows.size());
        for (SummaryRow row : rows) {
            Map<String, List<String>> codeMap = codesBySn.getOrDefault(row.sn(), Map.of());
            String effectiveStatus = CatalogViews.effectiveStatus(row.status(), row.endDate(), kstToday);
            result.add(new PostingSummary(
                    row.sn(),
                    row.instCode(),
                    row.instName(),
                    row.title(),
                    row.bgngDate(),
                    row.endDate(),
                    row.recrutSe(),
                    CatalogViews.recrutSeLabel(row.recrutSe()),
                    effectiveStatus,
                    CatalogViews.statusLabel(effectiveStatus),
                    CatalogViews.ddayLabel(effectiveStatus, row.endDate(), kstToday),
                    row.brokenText(),
                    codeMap.getOrDefault("NCS", List.of()),
                    codeMap.getOrDefault("REGION", List.of()),
                    codeMap.getOrDefault("HIRE", List.of())
            ));
        }
        return result;
    }

    private Map<Long, Map<String, List<String>>> loadCodesBySn(List<Long> sns) {
        if (sns.isEmpty()) {
            return Map.of();
        }
        Map<Long, Map<String, List<String>>> bySn = new HashMap<>();
        jdbc.sql("""
                        SELECT pc.sn, pc.type, COALESCE(cn.name, pc.code) AS display_name
                        FROM posting_code pc
                        LEFT JOIN code_name cn ON cn.type = pc.type AND cn.code = pc.code
                        WHERE pc.sn IN (:sns)
                        ORDER BY pc.sn ASC, pc.type ASC, pc.code ASC
                        """)
                .param("sns", sns)
                .query(rs -> {
                    long sn = rs.getLong("sn");
                    String type = rs.getString("type");
                    String displayName = rs.getString("display_name");
                    bySn.computeIfAbsent(sn, k -> new HashMap<>())
                            .computeIfAbsent(type, k -> new ArrayList<>())
                            .add(displayName);
                });
        return bySn;
    }

    private SummaryRow mapSummaryRow(ResultSet rs, int rowNum) throws SQLException {
        return new SummaryRow(
                rs.getLong("sn"),
                rs.getString("inst_code"),
                rs.getString("inst_name"),
                rs.getString("title"),
                rs.getObject("bgng_date", LocalDate.class),
                rs.getObject("end_date", LocalDate.class),
                rs.getString("recrut_se"),
                rs.getString("status"),
                rs.getBoolean("broken_text")
        );
    }

    private static String formatRecruitDisplay(String raw, Long numeric) {
        if (raw != null && !raw.isBlank()) {
            return raw;
        }
        if (numeric != null) {
            return numeric + "명";
        }
        return "미정";
    }

    record ParsedSteps(List<StepItem> items, boolean unknownFormat) {
    }

    record ParsedFiles(List<FileItem> items, boolean unknownFormat) {
    }

    /**
     * Parses official swagger detail step objects (unwrapped from {"step": ...} by #4).
     * Only fields with verified official semantics (sortNo, recrutNope, aplyNope, cmpttRt) are labeled;
     * unrecognized shapes never emit raw values.
     */
    ParsedSteps parseSteps(String json) {
        if (json == null || json.isBlank()) {
            return new ParsedSteps(List.of(), false);
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            if (!root.isArray()) {
                return new ParsedSteps(List.of(), true);
            }
            if (root.isEmpty()) {
                return new ParsedSteps(List.of(), false);
            }
            List<StepItem> items = new ArrayList<>();
            boolean unknownFormat = false;
            for (JsonNode node : root) {
                JsonNode stepNode = node.isObject() && node.path("step").isObject() ? node.path("step") : node;
                if (!stepNode.isObject()) {
                    unknownFormat = true;
                    continue;
                }
                String sortNo = nonBlankText(stepNode, "sortNo");
                String recruitNope = nonBlankText(stepNode, "recrutNope");
                String applyNope = nonBlankText(stepNode, "aplyNope");
                String competitionRate = nonBlankText(stepNode, "cmpttRt");

                List<String> details = new ArrayList<>();
                if (!recruitNope.isEmpty()) {
                    details.add("모집 인원: " + formatStepCount(recruitNope));
                }
                if (!applyNope.isEmpty()) {
                    details.add("지원자 수: " + formatStepCount(applyNope));
                }
                if (!competitionRate.isEmpty()) {
                    details.add("경쟁률: " + competitionRate);
                }
                if (details.isEmpty()) {
                    unknownFormat = true;
                    continue;
                }
                String title = sortNo.isEmpty() ? "전형 정보" : "전형 정보 " + sortNo;
                items.add(new StepItem(title, String.join("\n", details)));
            }
            return new ParsedSteps(items, unknownFormat);
        } catch (Exception ex) {
            return new ParsedSteps(List.of(), true);
        }
    }

    /**
     * Parses official swagger detail file objects (unwrapped from {"file": ...} by #4).
     * Reads atchFileNm and url only; unrecognized objects never emit raw values.
     */
    ParsedFiles parseFiles(String json) {
        if (json == null || json.isBlank()) {
            return new ParsedFiles(List.of(), false);
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            if (!root.isArray()) {
                return new ParsedFiles(List.of(), true);
            }
            if (root.isEmpty()) {
                return new ParsedFiles(List.of(), false);
            }
            List<FileItem> items = new ArrayList<>();
            boolean unknownFormat = false;
            for (JsonNode node : root) {
                JsonNode fileNode = node.isObject() && node.path("file").isObject() ? node.path("file") : node;
                if (!fileNode.isObject()) {
                    unknownFormat = true;
                    continue;
                }
                String name = nonBlankText(fileNode, "atchFileNm");
                String rawUrl = nonBlankText(fileNode, "url");
                String safeUrl = SafeUrlValidator.sanitizeHttpUrl(rawUrl);
                if (name.isEmpty() && safeUrl == null) {
                    unknownFormat = true;
                    continue;
                }
                items.add(new FileItem(name.isEmpty() ? "첨부파일" : name, safeUrl));
            }
            return new ParsedFiles(items, unknownFormat);
        } catch (Exception ex) {
            return new ParsedFiles(List.of(), true);
        }
    }

    private static String nonBlankText(JsonNode node, String key) {
        JsonNode value = node.get(key);
        if (value == null || value.isNull()) {
            return "";
        }
        String text = value.asString("");
        return text.isBlank() ? "" : text.trim();
    }

    private static String formatStepCount(String value) {
        return DIGITS.matcher(value).matches() ? value + "명" : value;
    }

    private record SummaryRow(
            long sn,
            String instCode,
            String instName,
            String title,
            LocalDate bgngDate,
            LocalDate endDate,
            String recrutSe,
            String status,
            boolean brokenText
    ) {
    }

    private record DetailRow(
            long sn,
            String instCode,
            String instName,
            String title,
            LocalDate bgngDate,
            LocalDate endDate,
            String recrutSe,
            String recrutNopeRaw,
            Long recrutNope,
            String aplyQlfc,
            String pref,
            String scrnprcdr,
            String srcUrl,
            boolean srcUrlGeneric,
            boolean brokenText,
            String status,
            String detailStatus,
            String stepsJson,
            String filesJson
    ) {
    }
}
