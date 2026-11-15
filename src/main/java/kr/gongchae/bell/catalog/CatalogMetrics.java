package kr.gongchae.bell.catalog;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class CatalogMetrics {
    public static final String INTERNAL_HEADER = "X-Gongchae-Internal";

    private static final List<String> INTERNAL_UA_TOKENS = List.of(
            "gongchae-probe", "uptimerobot", "healthchecks", "g4-deploy-check"
    );
    private static final List<String> BOT_UA_TOKENS = List.of(
            "bot", "crawler", "spider", "slurp", "bingpreview", "yandex",
            "duckduckbot", "facebookexternalhit", "headless", "curl/", "wget/",
            "python-requests", "go-http-client"
    );

    private final MeterRegistry registry;

    public CatalogMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordPageView(String page, HttpServletRequest request) {
        TrafficType traffic = classifyTraffic(request);
        Counter.builder("gongchae.page.views")
                .description("Catalog page views separated by traffic type")
                .tag("page", page)
                .tag("traffic", traffic.tag())
                .register(registry)
                .increment();
    }

    public void recordSourceRedirect(boolean generic, HttpServletRequest request) {
        TrafficType traffic = classifyTraffic(request);
        Counter.builder("gongchae.source.redirects")
                .description("Original posting source link redirects")
                .tag("generic", Boolean.toString(generic))
                .tag("traffic", traffic.tag())
                .register(registry)
                .increment();
    }

    public TrafficType classifyTraffic(HttpServletRequest request) {
        if (request == null) {
            return TrafficType.INTERNAL;
        }
        String internalHeader = request.getHeader(INTERNAL_HEADER);
        if ("true".equalsIgnoreCase(internalHeader) || "1".equals(internalHeader)) {
            return TrafficType.INTERNAL;
        }
        String userAgent = request.getHeader("User-Agent");
        if (userAgent == null || userAgent.isBlank()) {
            return TrafficType.BOT;
        }
        String lower = userAgent.toLowerCase(Locale.ROOT);
        for (String token : INTERNAL_UA_TOKENS) {
            if (lower.contains(token)) {
                return TrafficType.INTERNAL;
            }
        }
        for (String token : BOT_UA_TOKENS) {
            if (lower.contains(token)) {
                return TrafficType.BOT;
            }
        }
        return TrafficType.USER;
    }

    public enum TrafficType {
        USER("user"),
        BOT("bot"),
        INTERNAL("internal");

        private final String tag;

        TrafficType(String tag) {
            this.tag = tag;
        }

        public String tag() {
            return tag;
        }
    }
}
