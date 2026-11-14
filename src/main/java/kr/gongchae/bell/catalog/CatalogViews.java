package kr.gongchae.bell.catalog;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

public final class CatalogViews {
    private CatalogViews() {
    }

    public static String effectiveStatus(String storedStatus, LocalDate endDate, LocalDate kstToday) {
        if ("CLOSED".equals(storedStatus) || endDate.isBefore(kstToday)) {
            return "CLOSED";
        }
        if ("CHECK".equals(storedStatus)) {
            return "CHECK";
        }
        return "OPEN";
    }

    public static String statusLabel(String effectiveStatus) {
        return switch (effectiveStatus) {
            case "OPEN" -> "진행 중";
            case "CHECK" -> "확인 필요";
            case "CLOSED" -> "마감";
            default -> effectiveStatus;
        };
    }

    public static String ddayLabel(String effectiveStatus, LocalDate endDate, LocalDate kstToday) {
        long days = ChronoUnit.DAYS.between(kstToday, endDate);
        if ("CLOSED".equals(effectiveStatus) || days < 0) {
            return "마감";
        }
        return "D-" + days;
    }

    public static String recrutSeLabel(String recrutSe) {
        if (recrutSe == null || recrutSe.isBlank()) {
            return "미상";
        }
        return switch (recrutSe) {
            case "R2010" -> "신입";
            case "R2020" -> "경력";
            case "R2030" -> "신입+경력";
            default -> recrutSe;
        };
    }

    public record FilterOption(String code, String name) {
    }

    public record PostingSummary(
            long sn,
            String instCode,
            String instName,
            String title,
            LocalDate bgngDate,
            LocalDate endDate,
            String recrutSe,
            String recrutSeLabel,
            String effectiveStatus,
            String statusLabel,
            String ddayLabel,
            boolean brokenText,
            List<String> ncsNames,
            List<String> regionNames,
            List<String> hireNames
    ) {
    }

    public record JobSearchResult(
            List<PostingSummary> items,
            long totalCount,
            int page,
            int pageSize,
            int totalPages
    ) {
        public boolean hasPrev() {
            return page > 1;
        }

        public boolean hasNext() {
            return page < totalPages;
        }
    }

    public record StepItem(String title, String detail) {
    }

    public record FileItem(String name, String safeUrl) {
    }

    public record PostingDetail(
            long sn,
            String instCode,
            String instName,
            String title,
            LocalDate bgngDate,
            LocalDate endDate,
            String recrutSe,
            String recrutSeLabel,
            String recrutNopeDisplay,
            String aplyQlfc,
            String pref,
            String scrnprcdr,
            String safeSrcUrl,
            boolean srcUrlGeneric,
            String alioUrl,
            boolean brokenText,
            String effectiveStatus,
            String statusLabel,
            String ddayLabel,
            String detailStatus,
            List<StepItem> steps,
            List<FileItem> files,
            List<String> ncsNames,
            List<String> regionNames,
            List<String> hireNames,
            List<String> eduNames
    ) {
        public boolean hasCollectedDetail() {
            return "DONE".equals(detailStatus);
        }
    }

    public record PostingSource(long sn, String srcUrl, boolean srcUrlGeneric) {
    }

    public record InstitutionSummary(
            String code,
            String name,
            LocalDate fromDate,
            LocalDate toDate,
            long totalPostings,
            long totalPlannedRecruits,
            long excludedRecruitCount
    ) {
    }

    public record YearlyRecruitStat(
            int year,
            long postingCount,
            long plannedRecruits,
            long excludedCount
    ) {
    }

    public record MonthlyPatternRow(
            int year,
            int[] monthCounts,
            long yearTotal
    ) {
    }

    public record NcsDistributionRow(
            String code,
            String name,
            long postingCount
    ) {
    }

    public record InstitutionHistoryPage(
            List<PostingSummary> items,
            long totalCount,
            int page,
            int pageSize,
            int totalPages
    ) {
        public boolean hasPrev() {
            return page > 1;
        }

        public boolean hasNext() {
            return page < totalPages;
        }
    }

    public record InstitutionDetailView(
            InstitutionSummary summary,
            List<YearlyRecruitStat> yearlyRecruits,
            List<MonthlyPatternRow> monthlyPatterns,
            List<NcsDistributionRow> ncsDistribution,
            InstitutionHistoryPage history
    ) {
    }
}
