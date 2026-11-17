package kr.gongchae.bell.alerts;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;
import kr.gongchae.bell.catalog.CatalogRepository;
import kr.gongchae.bell.catalog.CatalogViews;
import kr.gongchae.bell.catalog.JobSearchCriteria;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** FR-A1~A3, A6~A8 pages. GET never changes state; every change is a POST (PRD alerts rule). */
@Controller
public class SubscriptionController {
    private final SubscriptionService service;
    private final AlertsProperties properties;
    private final CatalogRepository catalog;

    public SubscriptionController(SubscriptionService service, AlertsProperties properties, CatalogRepository catalog) {
        this.service = service; this.properties = properties; this.catalog = catalog;
    }

    /** Pages holding or showing a capability: never cached, never leak the URL through Referer (FR-A3). */
    private static void capabilityPage(HttpServletResponse response) {
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Robots-Tag", "noindex");
    }

    private static String ip(HttpServletRequest request) { return request.getRemoteAddr(); }

    // ---- sign-up ----

    @GetMapping("/subscriptions/new")
    String form(@RequestParam(required = false) String ncs, @RequestParam(required = false) String region,
            @RequestParam(required = false) String hire, @RequestParam(required = false) String career,
            @RequestParam(required = false) String q, Model model) {
        if (!properties.enabled()) return unavailable(model);
        Map<String, List<CatalogViews.FilterOption>> options = catalog.findFilterOptions();
        // Prefill from the list filters in the list's own ignore mode; the POST validates strictly.
        JobSearchCriteria prefill = JobSearchCriteria.normalize(ncs, region, hire, career, null, q, null, null, catalog.allowedCodeSet(options));
        model.addAttribute("form", new SubscriptionService.Conditions(prefill.ncs(), prefill.region(), prefill.hire(),
                prefill.career(), prefill.q(), true, true));
        return newPage(model, options, null, "email");
    }

    private String newPage(Model model, Map<String, List<CatalogViews.FilterOption>> options, String error, String tab) {
        model.addAttribute("title", "알림 신청 · 공채벨");
        model.addAttribute("description", "관심 조건의 새 공고와 마감 임박 공고를 이메일이나 디스코드로 받아 보세요.");
        model.addAttribute("filterOptions", options);
        model.addAttribute("privacy", properties.privacy());
        model.addAttribute("error", error);
        model.addAttribute("tab", tab);
        return "subscriptions/new";
    }

    @PostMapping("/subscriptions/email")
    String subscribeEmail(@RequestParam(required = false) String email, @RequestParam(required = false) String ncs,
            @RequestParam(required = false) String region, @RequestParam(required = false) String hire,
            @RequestParam(required = false) String career, @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "false") boolean notifyNew, @RequestParam(defaultValue = "false") boolean notifyDeadline,
            @RequestParam(defaultValue = "false") boolean consent, @RequestParam(defaultValue = "false") boolean age14,
            Model model, HttpServletRequest request, HttpServletResponse response) {
        if (!properties.enabled()) { response.setStatus(503); return unavailable(model); }
        SubscriptionService.Conditions conditions = new SubscriptionService.Conditions(blank(ncs), blank(region), blank(hire), blank(career), blank(q), notifyNew, notifyDeadline);
        try {
            conditions = service.conditions(ncs, region, hire, career, q, notifyNew, notifyDeadline);
            SubscriptionService.State state = service.subscribeEmail(email, conditions, consent, age14, ip(request));
            if (state == SubscriptionService.State.RATE_LIMITED) return limited(model, response);
            // One answer for every case (new, pending, active, send failure, send cap): it must not reveal any of them.
            return notice(model, "신청을 받았습니다",
                    "구독 중이 아닌 주소라면 확인 메일을 보냅니다. 메일의 링크에서 '신청 확인'을 눌러야 알림이 시작됩니다.",
                    "메일이 오지 않으면 스팸함을 확인하고, 잠시 뒤(하루 발송 한도에 걸렸다면 다음 날) 다시 신청하세요. 이미 구독 중인 주소라면 기존 구독은 그대로 유지됩니다.");
        } catch (SubscriptionService.InvalidInput invalid) {
            response.setStatus(400);
            model.addAttribute("form", conditions);
            model.addAttribute("email", email);
            return newPage(model, catalog.findFilterOptions(), invalid.field(), "email");
        }
    }

    @PostMapping("/subscriptions/discord")
    String subscribeDiscord(@RequestParam(required = false) String webhook, @RequestParam(required = false) String ncs,
            @RequestParam(required = false) String region, @RequestParam(required = false) String hire,
            @RequestParam(required = false) String career, @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "false") boolean notifyNew, @RequestParam(defaultValue = "false") boolean notifyDeadline,
            @RequestParam(defaultValue = "false") boolean consent, @RequestParam(defaultValue = "false") boolean age14,
            Model model, HttpServletRequest request, HttpServletResponse response) {
        if (!properties.enabled()) { response.setStatus(503); return unavailable(model); }
        SubscriptionService.Conditions conditions = new SubscriptionService.Conditions(blank(ncs), blank(region), blank(hire), blank(career), blank(q), notifyNew, notifyDeadline);
        SubscriptionService.Result result;
        try {
            conditions = service.conditions(ncs, region, hire, career, q, notifyNew, notifyDeadline);
            result = service.registerDiscord(webhook, conditions, consent, age14, ip(request));
        } catch (SubscriptionService.InvalidInput invalid) {
            response.setStatus(400);
            model.addAttribute("form", conditions);
            return newPage(model, catalog.findFilterOptions(), invalid.field(), "discord");
        }
        return switch (result.state()) {
            case REGISTERED -> {
                capabilityPage(response);
                model.addAttribute("manageLink", "/subscriptions/manage?t=" + result.manageToken());
                yield notice(model, "디스코드 알림을 등록했습니다",
                        "테스트 메시지가 채널에 도착해 등록을 마쳤습니다.",
                        "아래 관리 링크는 지금 한 번만 보여 드립니다. 채널 메시지에는 넣지 않으니 따로 보관하세요. 잃어버리면 '관리 링크 다시 받기'에서 웹훅 URL을 다시 입력하면 됩니다.");
            }
            case ALREADY -> notice(model, "이미 등록된 웹훅입니다",
                    "같은 웹훅으로 등록된 알림이 있어 테스트 메시지를 보내지 않았습니다.",
                    "관리 링크는 '관리 링크 다시 받기'에서 웹훅 URL을 입력하면 다시 볼 수 있습니다.");
            case WEBHOOK_NOT_FOUND -> { response.setStatus(400); yield notice(model, "웹훅을 찾을 수 없습니다",
                    "디스코드가 이 웹훅이 없다고 응답했습니다. 채널 설정에서 웹훅 URL을 다시 복사해 주세요.", null); }
            case RATE_LIMITED -> limited(model, response);
            default -> { response.setStatus(502); yield notice(model, "테스트 메시지를 보내지 못했습니다",
                    "디스코드에 테스트 메시지가 전송되지 않아 등록하지 않았습니다. 잠시 뒤 다시 시도해 주세요.", null); }
        };
    }

    // ---- confirm ----

    @GetMapping("/subscriptions/confirm")
    String confirmPage(@RequestParam(required = false) String t, Model model, HttpServletResponse response) {
        capabilityPage(response);
        if (!properties.enabled()) { response.setStatus(503); return unavailable(model); }
        model.addAttribute("title", "알림 신청 확인 · 공채벨");
        model.addAttribute("token", t);
        return "subscriptions/confirm";
    }

    @PostMapping("/subscriptions/confirm")
    String confirm(@RequestParam(required = false) String t, Model model, HttpServletRequest request, HttpServletResponse response) {
        capabilityPage(response);
        if (!properties.enabled()) { response.setStatus(503); return unavailable(model); }
        SubscriptionService.Result result = service.confirm(t, ip(request));
        return switch (result.state()) {
            case CONFIRMED -> {
                model.addAttribute("manageLink", "/subscriptions/manage?t=" + result.manageToken());
                yield notice(model, "알림 신청을 확인했습니다", "이제 조건에 맞는 공고 알림을 받습니다.",
                        "조건 수정과 해지는 아래 관리 링크에서 할 수 있습니다. 알림 메일에도 관리·해지 링크가 들어 있습니다.");
            }
            case BLOCKED -> limited(model, response);
            default -> invalidLink(model, response);
        };
    }

    // ---- manage ----

    @GetMapping("/subscriptions/manage")
    String managePage(@RequestParam(required = false) String t, Model model, HttpServletRequest request, HttpServletResponse response) {
        capabilityPage(response);
        if (!properties.enabled()) { response.setStatus(503); return unavailable(model); }
        SubscriptionService.ManageAccess access = service.manage(t, ip(request));
        if (access.state() == SubscriptionService.State.BLOCKED) return limited(model, response);
        if (access.view() == null) return invalidLink(model, response);
        return managePage(model, access.view(), access.view().conditions(), null);
    }

    private String managePage(Model model, SubscriptionService.ManageView view, SubscriptionService.Conditions form, String error) {
        model.addAttribute("title", "구독 관리 · 공채벨");
        model.addAttribute("view", view);
        model.addAttribute("form", form);
        model.addAttribute("error", error);
        model.addAttribute("filterOptions", catalog.findFilterOptions());
        return "subscriptions/manage";
    }

    @PostMapping("/subscriptions/manage")
    String manage(@RequestParam(required = false) String t, @RequestParam(defaultValue = "update") String action,
            @RequestParam(required = false) String ncs, @RequestParam(required = false) String region,
            @RequestParam(required = false) String hire, @RequestParam(required = false) String career,
            @RequestParam(required = false) String q, @RequestParam(defaultValue = "false") boolean notifyNew,
            @RequestParam(defaultValue = "false") boolean notifyDeadline,
            Model model, HttpServletRequest request, HttpServletResponse response) {
        capabilityPage(response);
        if (!properties.enabled()) { response.setStatus(503); return unavailable(model); }
        if (action.equals("unsubscribe")) return unsubscribed(service.unsubscribe(t, AlertsCrypto.Purpose.MANAGE, ip(request)), model, response);
        SubscriptionService.State state;
        try {
            state = service.update(t, service.conditions(ncs, region, hire, career, q, notifyNew, notifyDeadline), ip(request));
        } catch (SubscriptionService.InvalidInput invalid) {
            SubscriptionService.ManageAccess access = service.manage(t, ip(request));
            if (access.view() == null) return access.state() == SubscriptionService.State.BLOCKED ? limited(model, response) : invalidLink(model, response);
            response.setStatus(400);
            return managePage(model, access.view(), new SubscriptionService.Conditions(blank(ncs), blank(region), blank(hire),
                    blank(career), blank(q), notifyNew, notifyDeadline), invalid.field());
        }
        return switch (state) {
            case UPDATED -> {
                model.addAttribute("manageLink", "/subscriptions/manage?t=" + t);
                yield notice(model, "조건을 바꿨습니다", "다음 알림부터 바뀐 조건이 적용됩니다. 아직 보내지 않은 알림도 보내기 직전에 다시 확인합니다.", null);
            }
            case NOT_ACTIVE -> notice(model, "활성 상태가 아닌 구독입니다", "확인 전이거나 비활성화된 구독은 조건을 바꿀 수 없습니다. 해지는 할 수 있습니다.", null);
            case BLOCKED -> limited(model, response);
            default -> invalidLink(model, response);
        };
    }

    // ---- unsubscribe ----

    @GetMapping("/subscriptions/unsubscribe")
    String unsubscribePage(@RequestParam(required = false) String t, Model model, HttpServletResponse response) {
        capabilityPage(response);
        if (!properties.enabled()) { response.setStatus(503); return unavailable(model); }
        model.addAttribute("title", "알림 해지 · 공채벨");
        model.addAttribute("token", t);
        return "subscriptions/unsubscribe";
    }

    @PostMapping("/subscriptions/unsubscribe")
    String unsubscribe(@RequestParam(required = false) String t, Model model, HttpServletRequest request, HttpServletResponse response) {
        capabilityPage(response);
        if (!properties.enabled()) { response.setStatus(503); return unavailable(model); }
        return unsubscribed(service.unsubscribe(t, AlertsCrypto.Purpose.UNSUBSCRIBE, ip(request)), model, response);
    }

    /** RFC 8058 one-click (FR-A8): token in the URL and the exact body field; no page, no Origin from mail providers. */
    @PostMapping("/subscriptions/unsubscribe/one-click")
    ResponseEntity<String> oneClick(@RequestParam(required = false) String t,
            @RequestParam(name = "List-Unsubscribe", required = false) String body, HttpServletRequest request) {
        if (!properties.enabled()) return ResponseEntity.status(503).build();
        if (!"One-Click".equals(body)) return ResponseEntity.badRequest().build();
        SubscriptionService.State state = service.unsubscribe(t, AlertsCrypto.Purpose.UNSUBSCRIBE, ip(request));
        return switch (state) {
            case UNSUBSCRIBED -> ResponseEntity.ok("unsubscribed");
            case BLOCKED -> ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
            default -> ResponseEntity.badRequest().build();
        };
    }

    private String unsubscribed(SubscriptionService.State state, Model model, HttpServletResponse response) {
        return switch (state) {
            case UNSUBSCRIBED -> notice(model, "알림을 해지했습니다",
                    "구독 정보와 이 구독의 발송 기록을 삭제했습니다. 이미 보내기 시작한 알림 한 건은 도착할 수 있습니다.",
                    "백업과 메일·디스코드 공급자에 남은 사본은 개인정보처리방침에 적힌 기간이 지나야 사라집니다.");
            case BLOCKED -> limited(model, response);
            default -> invalidLink(model, response);
        };
    }

    // ---- recovery ----

    @GetMapping("/subscriptions/recover")
    String recoverPage(Model model) {
        if (!properties.enabled()) return unavailable(model);
        model.addAttribute("title", "관리 링크 다시 받기 · 공채벨");
        return "subscriptions/recover";
    }

    @PostMapping("/subscriptions/recover/email")
    String recoverEmail(@RequestParam(required = false) String email, Model model, HttpServletRequest request, HttpServletResponse response) {
        if (!properties.enabled()) { response.setStatus(503); return unavailable(model); }
        try {
            if (service.recoverEmail(email, ip(request)) == SubscriptionService.State.RATE_LIMITED) return limited(model, response);
        } catch (SubscriptionService.InvalidInput invalid) {
            response.setStatus(400);
            return notice(model, "이메일 주소를 확인해 주세요", "올바른 이메일 주소 형식이 아닙니다.", null);
        }
        return notice(model, "요청을 받았습니다", "구독 중인 주소라면 관리 링크를 메일로 보냅니다.",
                "메일이 오지 않으면 스팸함을 확인하고, 잠시 뒤 다시 요청하세요.");
    }

    @PostMapping("/subscriptions/recover/discord")
    String recoverDiscord(@RequestParam(required = false) String webhook, Model model, HttpServletRequest request, HttpServletResponse response) {
        capabilityPage(response);
        if (!properties.enabled()) { response.setStatus(503); return unavailable(model); }
        SubscriptionService.Result result;
        try { result = service.recoverDiscord(webhook, ip(request)); }
        catch (SubscriptionService.InvalidInput invalid) {
            response.setStatus(400);
            return notice(model, "웹훅 URL을 확인해 주세요", "디스코드 웹훅 URL 형식이 아닙니다.", null);
        }
        return switch (result.state()) {
            case FOUND -> {
                model.addAttribute("manageLink", "/subscriptions/manage?t=" + result.manageToken());
                yield notice(model, "관리 링크입니다", "아래 링크에서 조건을 바꾸거나 해지할 수 있습니다.", "이 링크는 다른 사람과 공유하지 마세요.");
            }
            case RATE_LIMITED -> limited(model, response);
            default -> notice(model, "일치하는 등록이 없습니다", "이 웹훅으로 등록된 알림이 없습니다.", null);
        };
    }

    // ---- privacy ----

    @GetMapping("/privacy")
    String privacy(Model model) {
        model.addAttribute("title", "개인정보처리방침 · 공채벨");
        model.addAttribute("description", "공채벨 알림 서비스의 개인정보 처리 방침입니다.");
        model.addAttribute("privacy", properties.privacy());
        return "privacy";
    }

    // ---- shared pages ----

    /**
     * A lock or statement wait bounded by the in-request budget ran out (14-4). Nothing of the step that timed out was
     * committed. Counted requests and reserved sends stay counted, as always.
     */
    @ExceptionHandler({CannotAcquireLockException.class, QueryTimeoutException.class})
    String busy(Model model, HttpServletResponse response) {
        response.setStatus(503);
        return notice(model, "지금은 요청을 처리하지 못했습니다", "요청이 몰려 제한 시간 안에 처리하지 못했습니다. 잠시 뒤 다시 시도해 주세요.", null);
    }

    private static String notice(Model model, String heading, String message, String detail) {
        model.addAttribute("title", heading + " · 공채벨");
        model.addAttribute("heading", heading);
        model.addAttribute("message", message);
        model.addAttribute("detail", detail);
        return "subscriptions/notice";
    }

    private static String invalidLink(Model model, HttpServletResponse response) {
        response.setStatus(400);
        return notice(model, "링크를 쓸 수 없습니다", "링크가 잘못됐거나, 이미 쓰였거나, 기간이 지났습니다.",
                "확인 링크는 24시간 동안 한 번만 쓸 수 있습니다. 관리 링크를 잃어버렸다면 '관리 링크 다시 받기'를 이용하세요.");
    }

    private static String limited(Model model, HttpServletResponse response) {
        response.setStatus(429);
        return notice(model, "요청이 너무 많습니다", "같은 요청이 짧은 시간에 여러 번 들어왔습니다. 잠시 뒤 다시 시도해 주세요.", null);
    }

    private static String unavailable(Model model) {
        return notice(model, "알림 신청은 준비 중입니다", "채용공고 조회는 지금 바로 이용할 수 있습니다. 알림 기능은 운영 준비를 마친 뒤 열립니다.", null);
    }

    private static String blank(String value) { return value == null || value.isBlank() ? null : value.trim(); }
}
