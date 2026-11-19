package kr.gongchae.bell.alerts;

import kr.gongchae.bell.ops.Heartbeat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

@Configuration
@EnableConfigurationProperties({AlertsProperties.class, AlertDispatchProperties.class})
public class AlertsConfiguration {

    @Bean
    Heartbeat digestHeartbeat(@Value("${heartbeat.digest-url:}") String url) {
        return new Heartbeat("digest", url);
    }

    /** Lets every page show or hide the subscription entry points (list CTA) without knowing the alerts package. */
    @ControllerAdvice
    static class AlertsModel {
        private final AlertsProperties properties;
        AlertsModel(AlertsProperties properties) { this.properties = properties; }

        @ModelAttribute
        void alerts(Model model) { model.addAttribute("alertsEnabled", properties.enabled()); }
    }
}
