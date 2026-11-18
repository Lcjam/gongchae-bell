package kr.gongchae.bell.alerts;

import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import kr.gongchae.bell.alerts.DeliveryLedger.Claim;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Bounded asynchronous delivery worker (#7): recovers stale SENDING claims (> 5 m), claims due async jobs via
 * {@code FOR UPDATE SKIP LOCKED}, and dispatches them across at most {@code workerConcurrency} (default 2) threads
 * so concurrent A+R connection pairs stay well within the 10-connection Hikari pool.
 */
@Component
public class AlertWorker {
    private static final Logger log = LoggerFactory.getLogger(AlertWorker.class);

    private final AlertsProperties properties;
    private final AlertDispatchProperties dispatchProperties;
    private final DeliveryLedger ledger;
    private final AlertDispatcher dispatcher;
    private final ExecutorService executor;

    public AlertWorker(AlertsProperties properties, AlertDispatchProperties dispatchProperties,
            DeliveryLedger ledger, AlertDispatcher dispatcher) {
        this.properties = properties;
        this.dispatchProperties = dispatchProperties;
        this.ledger = ledger;
        this.dispatcher = dispatcher;
        AtomicInteger seq = new AtomicInteger(1);
        this.executor = Executors.newFixedThreadPool(dispatchProperties.workerConcurrency(), r -> {
            Thread t = new Thread(r, "alert-worker-" + seq.getAndIncrement());
            t.setDaemon(false);
            return t;
        });
    }

    /** Recovers stale SENDING jobs then claims and processes one due batch. Returns number of claimed jobs processed. */
    public int runOnce() {
        if (!properties.enabled()) return 0;
        ledger.recoverAsync();
        List<Claim> claims = ledger.claimDue(dispatchProperties.batchSize());
        if (claims.isEmpty()) return 0;

        List<Future<?>> futures = new ArrayList<>(claims.size());
        for (Claim claim : claims) {
            futures.add(executor.submit(() -> {
                try {
                    dispatcher.dispatchClaim(claim);
                } catch (RuntimeException failure) {
                    log.warn("ALERT_WORKER_DISPATCH_FAILED {}", failure.getClass().getSimpleName());
                }
            }));
        }
        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            } catch (ExecutionException ignored) {
                // Logged inside task wrapper; remaining claims finish cleanly.
            }
        }
        return claims.size();
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(25, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException interrupted) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
