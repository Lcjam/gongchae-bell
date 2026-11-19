package kr.gongchae.bell.alerts;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Verifies {@code application.yml} env bindings ({@code ALERTS_WORKER_CONCURRENCY}, {@code ALERTS_BATCH_SIZE},
 * {@code ALERTS_SCHEDULER_ENABLED}) and {@link AlertScheduler} disabling without requiring a full DB container.
 */
class AlertDispatchPropertiesTest {

    @Configuration
    @EnableConfigurationProperties({AlertsProperties.class, AlertDispatchProperties.class})
    static class DispatchConfig {
        @Bean Clock clock() { return Clock.systemUTC(); }
        @Bean AlertBatchGenerator batchGenerator() { return org.mockito.Mockito.mock(AlertBatchGenerator.class); }
        @Bean AlertWorker worker() { return org.mockito.Mockito.mock(AlertWorker.class); }
    }

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(DispatchConfig.class, AlertScheduler.class)
                .withPropertyValues("DB_USER=test", "DB_PASSWORD=test");
    }

    @Test
    void defaultsBindAndRegisterScheduler() {
        runner().run(ctx -> {
            assertThat(ctx).hasNotFailed();
            AlertDispatchProperties props = ctx.getBean(AlertDispatchProperties.class);
            assertThat(props.workerConcurrency()).isEqualTo(2);
            assertThat(props.batchSize()).isEqualTo(20);
            assertThat(props.schedulerEnabled()).isTrue();
            assertThat(ctx).hasSingleBean(AlertScheduler.class);
        });
    }

    @Test
    void nonDefaultEnvOverridesBindAndDisableScheduler() {
        runner()
                .withPropertyValues(
                        "ALERTS_WORKER_CONCURRENCY=1",
                        "ALERTS_BATCH_SIZE=3",
                        "ALERTS_SCHEDULER_ENABLED=false")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    AlertDispatchProperties props = ctx.getBean(AlertDispatchProperties.class);
                    assertThat(props.workerConcurrency()).isEqualTo(1);
                    assertThat(props.batchSize()).isEqualTo(3);
                    assertThat(props.schedulerEnabled()).isFalse();
                    assertThat(ctx).doesNotHaveBean(AlertScheduler.class);
                });
    }

    @Test
    void workerConcurrencyAboveFourFailsStartup() {
        runner()
                .withPropertyValues("ALERTS_WORKER_CONCURRENCY=5")
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(ctx.getStartupFailure()).hasStackTraceContaining("ALERTS_WORKER_CONCURRENCY_TOO_HIGH");
                });
    }
}
