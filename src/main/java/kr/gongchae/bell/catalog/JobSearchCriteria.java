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

    /**
     * Subscription input (#6): the same allow-lists and query rule as {@link #normalize}, but a non-blank value outside
     * them is an error instead of being ignored, and an over-long query is refused instead of cut. Blank means "all".
     * Status, sort and page are not part of a subscription.
     */
    public static JobSearchCriteria strict(
            String ncs,
            String region,
            String hire,
            String career,
            String q,
            Map<String, Set<String>> allowedCodes
    ) {
        String query = q == null || q.isBlank() ? null : q.trim();
        if (query != null && query.length() > MAX_QUERY_LENGTH) {
            throw new InvalidCondition("q");
        }
        return new JobSearchCriteria(
                requireAllowed("ncs", ncs, allowedCodes.getOrDefault("NCS", Set.of())),
                requireAllowed("region", region, allowedCodes.getOrDefault("REGION", Set.of())),
                requireAllowed("hire", hire, allowedCodes.getOrDefault("HIRE", Set.of())),
                requireAllowed("career", career, ALLOWED_CAREER),
                "open", query, "deadline", 1);
    }

    /** A subscription condition outside the allow-list; {@code field} names the input. */
    public static final class InvalidCondition extends IllegalArgumentException {
        private final String field;

        public InvalidCondition(String field) {
            super("INVALID_CONDITION");
            this.field = field;
        }

        public String field() {
            return field;
        }
    }

    private static String requireAllowed(String field, String raw, Set<String> allowed) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim();
        if (!allowed.contains(trimmed)) {
            throw new InvalidCondition(field);
        }
        return trimmed;
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
