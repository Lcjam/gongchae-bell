package kr.gongchae.bell.catalog;

import java.util.Map;
import java.util.Set;

public record JobSearchCriteria(
        String ncs,
        String region,
        String hire,
        String career,
        String status,
        String q,
        String sort,
        int page
) {
    public static final int PAGE_SIZE = 20;
    public static final int MAX_QUERY_LENGTH = 100;

    private static final Set<String> ALLOWED_CAREER = Set.of("newcomer", "newonly", "career");
    private static final Set<String> ALLOWED_STATUS = Set.of("open", "check", "closed", "all");
    private static final Set<String> ALLOWED_SORT = Set.of("deadline", "begin");

    public static JobSearchCriteria normalize(
            String ncs,
            String region,
            String hire,
            String career,
            String status,
            String q,
            String sort,
            String page,
            Map<String, Set<String>> allowedCodes
    ) {
        String validNcs = allowCode(ncs, allowedCodes.getOrDefault("NCS", Set.of()));
        String validRegion = allowCode(region, allowedCodes.getOrDefault("REGION", Set.of()));
        String validHire = allowCode(hire, allowedCodes.getOrDefault("HIRE", Set.of()));
        String validCareer = allowEnum(career, ALLOWED_CAREER, null);
        String validStatus = allowEnum(status, ALLOWED_STATUS, "open");
        String validSort = allowEnum(sort, ALLOWED_SORT, "deadline");
        String validQ = normalizeQuery(q);
        int validPage = parsePositiveInt(page, 1);
        return new JobSearchCriteria(validNcs, validRegion, validHire, validCareer, validStatus, validQ, validSort, validPage);
    }

    public static int parsePositiveInt(String raw, int fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(raw.trim());
            return parsed >= 1 ? parsed : fallback;
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    public static String escapeLike(String query) {
        return query
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

    public String likePattern() {
        return q == null ? null : "%" + escapeLike(q) + "%";
    }

    public boolean hasActiveFilter() {
        return ncs != null
                || region != null
                || hire != null
                || career != null
                || q != null
                || !"open".equals(status)
                || !"deadline".equals(sort);
    }

    private static String allowCode(String raw, Set<String> allowed) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim();
        return allowed.contains(trimmed) ? trimmed : null;
    }

    private static String allowEnum(String raw, Set<String> allowed, String fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String trimmed = raw.trim();
        return allowed.contains(trimmed) ? trimmed : fallback;
    }

    private static String normalizeQuery(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() > MAX_QUERY_LENGTH
                ? trimmed.substring(0, MAX_QUERY_LENGTH)
                : trimmed;
    }
}
