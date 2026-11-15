package kr.gongchae.bell.ingestion.runtime;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.LocalDate;
import kr.gongchae.bell.GongchaeBellApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.support.GenericApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class BackfillCommandIntegrationTest {
    @Container static PostgreSQLContainer postgres=new PostgreSQLContainer("postgres:17");
    @Test void localCommandAppliesRealCoreAndClosesContextWithoutStartingWebOrCallingDetails() {
        var client=mock(RecruitmentClient.class);
        when(client.backfillList(LocalDate.of(2026,11,1),1))
                .thenReturn("{\"resultCode\":200,\"totalCount\":0,\"result\":[]}");
        var context=new SpringApplicationBuilder(GongchaeBellApplication.class).web(WebApplicationType.NONE)
                .initializers(application -> ((GenericApplicationContext)application).registerBean("fixtureRecruitmentClient",
                        RecruitmentClient.class,()->client,bean->bean.setPrimary(true)))
                .run("--spring.datasource.url="+postgres.getJdbcUrl(),"--spring.datasource.username="+postgres.getUsername(),
                        "--spring.datasource.password="+postgres.getPassword(),"--collector.enabled=false","--collector.backfill=true",
                        "--collector.service-key=fixture","--collector.backfill-slot=command-test",
                        "--collector.backfill-from=2026-11-01","--collector.backfill-to=2026-11-30");
        assertThat(context.isActive()).isFalse();
        verify(client).backfillList(LocalDate.of(2026,11,1),1);verifyNoMoreInteractions(client);
    }
}
