package kr.gongchae.bell.alerts;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

@Configuration
@EnableConfigurationProperties(AlertsProperties.class)
public class AlertsConfiguration {

    /** Lets every page show or hide the subscription entry points (list CTA) without knowing the alerts package. */
    @ControllerAdvice
    static class AlertsModel {
        private final AlertsProperties properties;
        AlertsModel(AlertsProperties properties) { this.properties = properties; }

        @ModelAttribute
        void alerts(Model model) { model.addAttribute("alertsEnabled", properties.enabled()); }
    }
}
