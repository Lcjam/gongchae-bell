package kr.gongchae.bell.ingestion.runtime;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(CollectorProperties.class)
public class CollectorConfiguration {
}
