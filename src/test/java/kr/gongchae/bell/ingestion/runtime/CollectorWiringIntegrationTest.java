package kr.gongchae.bell.ingestion.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import kr.gongchae.bell.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.util.ReflectionTestUtils;

class CollectorWiringIntegrationTest extends IntegrationTestSupport {
    @Autowired AdvisoryLease leases;
    @Autowired DataSource dataSource;
    @Autowired ThreadPoolTaskScheduler taskScheduler;

    @Test
    void leaseConnectionIsNotBorrowedFromThePool() {
        var pool = ((HikariDataSource) dataSource).getHikariPoolMXBean();
        int before = pool.getActiveConnections();
        try (var lease = leases.acquire(AdvisoryLease.DETAILS).orElseThrow()) {
            lease.verify();
            assertThat(pool.getActiveConnections()).isEqualTo(before);
        }
    }

    @Test
    void schedulerWaitsThirtySecondsForARunningCollectionOnShutdown() {
        assertThat(ReflectionTestUtils.getField(taskScheduler, "waitForTasksToCompleteOnShutdown")).isEqualTo(true);
        assertThat(ReflectionTestUtils.getField(taskScheduler, "awaitTerminationMillis")).isEqualTo(30_000L);
    }
}
