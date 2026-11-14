package kr.gongchae.bell.catalog;

import java.time.OffsetDateTime;
import java.time.Clock;
import java.time.format.DateTimeFormatter;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
public class CatalogController {
    private final JdbcClient jdbc;
    private final Clock clock;

    public CatalogController(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    void freshness(Model model) {
        var completed = jdbc.sql("SELECT max(finished_at) FROM collect_run WHERE kind = 'REGULAR' AND result = 'COMPLETE'")
                .query(OffsetDateTime.class).optional();
        model.addAttribute("lastCollected", completed.map(time -> time.atZoneSameInstant(clock.getZone())
                .format(DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm 'KST'"))).orElse("아직 완전 수집 기록이 없습니다"));
    }

    @GetMapping("/")
    String home(Model model) {
        freshness(model);
        model.addAttribute("title", "공채벨 · 공공기관 채용을 한눈에");
        model.addAttribute("description", "공공기관 채용공고와 기관별 채용 이력을 살펴보세요.");
        return "home";
    }

    @GetMapping("/jobs")
    String jobs(Model model) {
        freshness(model);
        model.addAttribute("title", "채용공고 · 공채벨");
        model.addAttribute("description", "직무, 지역, 채용구분별 공공기관 채용공고를 찾으세요.");
        return "jobs";
    }

    @GetMapping(value = "/robots.txt", produces = "text/plain")
    @ResponseBody
    String robots() {
        return "User-agent: *\nAllow: /\nDisallow: /subscriptions/\n";
    }
}
