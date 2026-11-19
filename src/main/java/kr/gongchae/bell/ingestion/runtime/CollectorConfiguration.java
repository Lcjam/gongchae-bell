package kr.gongchae.bell.ingestion.runtime;

import kr.gongchae.bell.ops.Heartbeat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(CollectorProperties.class)
public class CollectorConfiguration {
    @Bean
    Heartbeat collectorHeartbeat(@Value("${heartbeat.collector-url:}") String url) {
        return new Heartbeat("collector", url);
    }
}
