package kr.gongchae.bell.alerts;

import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * {@code --spring.main.web-application-type=none --alerts.erasure-reapply=true}. The whole journal is validated first.
 * A missing journal file (wrong path, mount not restored) or a torn or malformed line stops the command before
 * anything is deleted (fail closed): no completion log, and the run fails, so the process exits non-zero. Alerts
 * stay off until an operator has checked the journal. Otherwise every listed subscription is deleted again in one
 * transaction. Only an existing empty file (prepared, nothing erased yet) is a valid empty journal.
 */
@Component
@ConditionalOnProperty(name = "alerts.erasure-reapply", havingValue = "true")
public class ErasureReapplyCommand implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(ErasureReapplyCommand.class);
    private final ErasureJournal journal;
    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final ConfigurableApplicationContext context;

    public ErasureReapplyCommand(ErasureJournal journal, JdbcClient jdbc, PlatformTransactionManager manager, ConfigurableApplicationContext context) {
        this.journal = journal; this.jdbc = jdbc; this.tx = new TransactionTemplate(manager); this.context = context;
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
        List<UUID> ids = journal.readStrict();
        Integer deleted = tx.execute(status -> {
            int total = 0;
            for (int from = 0; from < ids.size(); from += 1000)
                total += jdbc.sql("DELETE FROM subscription WHERE id IN (:ids)").param("ids", ids.subList(from, Math.min(ids.size(), from + 1000))).update();
            return total;
        });
        return deleted == null ? 0 : deleted;
    }
}
