package kr.gongchae.bell.catalog;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.function.Supplier;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class FreshnessModelInterceptor implements HandlerInterceptor, WebMvcConfigurer {
    /**
     * Request attribute: a {@code Supplier<FreshnessStatus>} that a request with its own time budget (the alert sends)
     * uses instead of the unbounded read below.
     */
    public static final String STATUS_SOURCE = FreshnessModelInterceptor.class.getName() + ".source";

    private final FreshnessService freshnessService;

    public FreshnessModelInterceptor(FreshnessService freshnessService) {
        this.freshnessService = freshnessService;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this);
    }

    @Override
    public void postHandle(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler,
            ModelAndView modelAndView
    ) {
        if (modelAndView == null || !modelAndView.hasView()) {
            return;
        }
        String viewName = modelAndView.getViewName();
        if (viewName != null && (viewName.startsWith("redirect:") || viewName.startsWith("forward:"))) {
            return;
        }
        if (!modelAndView.getModel().containsKey("freshness")) {
            FreshnessService.FreshnessStatus status = request.getAttribute(STATUS_SOURCE) instanceof Supplier<?> budgeted
                    ? (FreshnessService.FreshnessStatus) budgeted.get()
                    : freshnessService.currentStatusSafe();
            modelAndView.addObject("freshness", status);
            modelAndView.addObject("lastCollected", status.display());
        }
    }
}
