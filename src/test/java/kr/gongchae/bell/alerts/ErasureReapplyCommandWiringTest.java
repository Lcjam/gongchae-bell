package kr.gongchae.bell.alerts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

/** The restore command is only created on request, and its bundle path really comes from {@code alerts.erasure-bundle-log}. */
class ErasureReapplyCommandWiringTest {
    @TempDir Path dir;
    final JdbcClient jdbc = mock(JdbcClient.class);

    ApplicationContextRunner runner(Path latest) {
        return new ApplicationContextRunner()
                .withBean(ErasureJournal.class, () -> new ErasureJournal(latest))
                .withBean(JdbcClient.class, () -> jdbc)
                .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
                .withUserConfiguration(ErasureReapplyCommand.class);
    }

    @Test
    void theBundlePropertyIsRequiredAndReadBeforeAnyDatabaseWork() throws Exception {
        Path latest = Files.writeString(dir.resolve("erasure.log"), "");
        runner(latest).run(context -> assertThat(context).doesNotHaveBean(ErasureReapplyCommand.class));
        runner(latest).withPropertyValues("alerts.erasure-reapply=true").run(context ->
                assertThatThrownBy(() -> context.getBean(ErasureReapplyCommand.class).reapply()).hasMessage("ERASURE_BUNDLE_REQUIRED"));
        runner(latest).withPropertyValues("alerts.erasure-reapply=true", "alerts.erasure-bundle-log=" + dir.resolve("absent.log"))
                .run(context -> assertThatThrownBy(() -> context.getBean(ErasureReapplyCommand.class).reapply())
                        .hasMessage("ERASURE_BUNDLE_MISSING"));
        verifyNoInteractions(jdbc);
    }
}
