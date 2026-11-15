package kr.gongchae.bell.catalog;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.ModelAndView;

@Controller
public class CatalogController {
    private static final int SITEMAP_INSTITUTION_LIMIT = 1000;
    private static final int SITEMAP_POSTING_LIMIT = 5000;

    private final CatalogRepository repository;
    private final FreshnessService freshnessService;
    private final CatalogMetrics metrics;
    private final Clock clock;
    private final String baseUrl;

    public CatalogController(
            CatalogRepository repository,
            FreshnessService freshnessService,
            CatalogMetrics metrics,
            Clock clock,
            @Value("${app.base-url:http://localhost:8080}") String baseUrl
    ) {
        this.repository = repository;
        this.freshnessService = freshnessService;
        this.metrics = metrics;
        this.clock = clock;
        this.baseUrl = trimTrailingSlash(baseUrl);
    }

    @GetMapping("/")
    String home(Model model, HttpServletRequest request) {
        metrics.recordPageView("home", request);
        LocalDate kstToday = LocalDate.now(clock);
        model.addAttribute("title", "공채벨 · 공공기관 채용을 한눈에");
        model.addAttribute("description", "공공기관 채용공고와 기관별 최근 3년 채용 이력을 살펴보세요.");
        model.addAttribute("closingSoon", repository.findClosingSoon(kstToday));
        model.addAttribute("startedToday", repository.findStartedToday(kstToday));
        return "home";
    }

    @GetMapping("/jobs")
    String jobs(
            @RequestParam(required = false) String ncs,
            @RequestParam(required = false) String region,
            @RequestParam(required = false) String hire,
            @RequestParam(required = false) String career,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String page,
            Model model,
            HttpServletRequest request
    ) {
        metrics.recordPageView("jobs", request);
        LocalDate kstToday = LocalDate.now(clock);
        Map<String, List<CatalogViews.FilterOption>> filterOptions = repository.findFilterOptions();
        Map<String, Set<String>> allowedCodes = repository.allowedCodeSet(filterOptions);
        JobSearchCriteria criteria = JobSearchCriteria.normalize(
                ncs, region, hire, career, status, q, sort, page, allowedCodes);
        CatalogViews.JobSearchResult result = repository.searchJobs(criteria, kstToday);
        boolean hasAnyPosting = result.items().isEmpty() && !criteria.hasActiveFilter() && repository.hasAnyPosting();

        model.addAttribute("title", "채용공고 · 공채벨");
        model.addAttribute("description", "직무, 지역, 고용형태, 채용구분별 공공기관 채용공고를 비교하세요.");
        model.addAttribute("filterOptions", filterOptions);
        model.addAttribute("criteria", criteria);
        model.addAttribute("result", result);
        model.addAttribute("hasAnyPosting", hasAnyPosting);
        model.addAttribute("pageNumbers", buildPageWindow(result.page(), result.totalPages()));
        return "jobs";
    }

    @GetMapping("/jobs/{sn}")
    String jobDetail(@PathVariable long sn, Model model, HttpServletRequest request) {
        LocalDate kstToday = LocalDate.now(clock);
        CatalogViews.PostingDetail job = repository.findJobDetail(sn, kstToday)
                .orElseThrow(PostingNotFoundException::new);
        metrics.recordPageView("job_detail", request);

        model.addAttribute("title", job.title() + " · " + job.instName() + " · 공채벨");
        model.addAttribute("description", job.instName() + " 채용공고 (" + job.bgngDate() + " ~ " + job.endDate() + ")");
        model.addAttribute("job", job);
        return "job-detail";
    }

    @GetMapping("/jobs/{sn}/source")
    ResponseEntity<Void> redirectToSource(@PathVariable long sn, HttpServletRequest request) {
        CatalogViews.PostingSource source = repository.findPostingSource(sn)
                .orElseThrow(PostingNotFoundException::new);
        String safeUrl = SafeUrlValidator.sanitizeHttpUrl(source.srcUrl());
        if (safeUrl == null) {
            throw new PostingNotFoundException();
        }
        metrics.recordSourceRedirect(source.srcUrlGeneric(), request);
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(safeUrl))
                .header("Referrer-Policy", "no-referrer")
                .build();
    }

    @GetMapping("/institutions/{code}")
    String institution(
            @PathVariable String code,
            @RequestParam(required = false) String page,
            Model model,
            HttpServletRequest request
    ) {
        if (code == null || code.isBlank() || code.length() > 40) {
            throw new PostingNotFoundException();
        }
        LocalDate kstToday = LocalDate.now(clock);
        int requestedPage = JobSearchCriteria.parsePositiveInt(page, 1);
        CatalogViews.InstitutionDetailView detail = repository.findInstitutionDetail(code.trim(), kstToday, requestedPage)
                .orElseThrow(PostingNotFoundException::new);
        metrics.recordPageView("institution", request);

        model.addAttribute("title", detail.summary().name() + " 채용 이력 · 공채벨");
        model.addAttribute("description", detail.summary().name() + " 최근 3년 공공기관 채용 이력, 시기 패턴, 직무 분포");
        model.addAttribute("institution", detail);
        model.addAttribute("pageNumbers", buildPageWindow(detail.history().page(), detail.history().totalPages()));
        return "institution";
    }

    @GetMapping(value = "/robots.txt", produces = MediaType.TEXT_PLAIN_VALUE)
    @ResponseBody
    String robots() {
        return "User-agent: *\nAllow: /\nDisallow: /subscriptions/\n";
    }

    @GetMapping(value = "/sitemap.xml", produces = MediaType.APPLICATION_XML_VALUE)
    @ResponseBody
    String sitemap() {
        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n");
        appendUrl(xml, baseUrl + "/");
        appendUrl(xml, baseUrl + "/jobs");
        for (String instCode : repository.findInstitutionCodesForSitemap(SITEMAP_INSTITUTION_LIMIT)) {
            appendUrl(xml, baseUrl + "/institutions/" + URLEncoder.encode(instCode, StandardCharsets.UTF_8));
        }
        for (Long sn : repository.findRecentPostingSnsForSitemap(SITEMAP_POSTING_LIMIT)) {
            appendUrl(xml, baseUrl + "/jobs/" + sn);
        }
        xml.append("</urlset>\n");
        return xml.toString();
    }

    @ExceptionHandler({PostingNotFoundException.class, MethodArgumentTypeMismatchException.class})
    @ResponseStatus(HttpStatus.NOT_FOUND)
    ModelAndView handleNotFound() {
        FreshnessService.FreshnessStatus status = freshnessService.currentStatusSafe();
        ModelAndView mav = new ModelAndView("error/404", HttpStatus.NOT_FOUND);
        mav.addObject("title", "페이지를 찾을 수 없습니다 · 공채벨");
        mav.addObject("description", "요청하신 채용공고 또는 기관 정보를 찾을 수 없습니다.");
        mav.addObject("freshness", status);
        mav.addObject("lastCollected", status.display());
        return mav;
    }

    static List<Integer> buildPageWindow(int currentPage, int totalPages) {
        if (totalPages <= 0) {
            return List.of();
        }
        int start = Math.max(1, currentPage - 2);
        int end = Math.min(totalPages, start + 4);
        start = Math.max(1, end - 4);
        List<Integer> pages = new ArrayList<>();
        for (int p = start; p <= end; p++) {
            pages.add(p);
        }
        return pages;
    }

    private static void appendUrl(StringBuilder xml, String loc) {
        xml.append("  <url><loc>").append(escapeXml(loc)).append("</loc></url>\n");
    }

    private static String escapeXml(String value) {
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    private static String trimTrailingSlash(String url) {
        if (url == null || url.isBlank()) {
            return "http://localhost:8080";
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    @ResponseStatus(HttpStatus.NOT_FOUND)
    static class PostingNotFoundException extends RuntimeException {
    }
}
