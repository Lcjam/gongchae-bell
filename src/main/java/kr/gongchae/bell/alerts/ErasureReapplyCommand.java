package kr.gongchae.bell.alerts;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Restore step of ADR-0013, run with alerts disabled after a DB restore:
 * {@code --spring.main.web-application-type=none --alerts.erasure-reapply=true --alerts.erasure-bundle-log=<B>}.
 * L is the journal to apply ({@code alerts.erasure-log}); B is the journal that came with the restored dump, required.
 * Before anything is deleted, both are parsed strictly and B must be covered by L ({@link ErasureJournal#verifyCoverage}).
 * A missing B or L, a torn or malformed line, a repeated marker or a coverage mismatch stops the command before the
 * DELETE (fail closed): no completion log, and the run fails, so the process exits non-zero. A passed gate logs
 * {@code ERASURE_COVERAGE_VERIFIED}; only a finished delete logs {@code ERASURE_REAPPLY_COMPLETE}. Markers are never
 * deleted as subscriptions. Every listed subscription is deleted again in one transaction, so a rerun is harmless.
 */
@Component
@ConditionalOnProperty(name = "alerts.erasure-reapply", havingValue = "true")
public class ErasureReapplyCommand implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(ErasureReapplyCommand.class);
    private final ErasureJournal journal;
    private final Path bundle;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final ConfigurableApplicationContext context;

    public ErasureReapplyCommand(ErasureJournal journal, @Value("${alerts.erasure-bundle-log:}") String bundleLog,
            JdbcClient jdbc, PlatformTransactionManager manager, ConfigurableApplicationContext context) {
        this.journal = journal;
        this.bundle = AlertsProperties.present(bundleLog) ? Path.of(bundleLog) : null;
        this.jdbc = jdbc; this.tx = new TransactionTemplate(manager); this.context = context;
    }

    @Override
    public void run(ApplicationArguments arguments) throws Exception {
        try {
            int deleted = reapply();
            log.info("ERASURE_REAPPLY_COMPLETE deleted={}", deleted);
        } finally {
            context.close();
        }
    }

    /** Deleted row count; throws {@link ErasureJournal.CorruptJournal} without deleting anything. */
    public int reapply() throws java.io.IOException {
        if (bundle == null) throw new ErasureJournal.CorruptJournal("ERASURE_BUNDLE_REQUIRED");
        ErasureJournal.Snapshot latest = journal.readLatest();
        ErasureJournal.Snapshot bundled = ErasureJournal.readBundle(bundle);
        ErasureJournal.Coverage coverage = ErasureJournal.verifyCoverage(bundled, latest);
        log.info("ERASURE_COVERAGE_VERIFIED rule={}", coverage);
        List<UUID> ids = latest.subscriptionIds();
        Integer deleted = tx.execute(status -> {
            int total = 0;
            for (int from = 0; from < ids.size(); from += 1000)
                total += jdbc.sql("DELETE FROM subscription WHERE id IN (:ids)").param("ids", ids.subList(from, Math.min(ids.size(), from + 1000))).update();
            return total;
        });
        return deleted == null ? 0 : deleted;
    }
}
