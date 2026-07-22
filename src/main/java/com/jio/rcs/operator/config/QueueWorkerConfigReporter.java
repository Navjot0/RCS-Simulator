package com.jio.rcs.operator.config;

import com.jio.rcs.operator.queue.QueueNames;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Prints one human-scannable summary of the resolved per-stage queue worker
 * configuration at startup, and separately warns (without failing startup)
 * if the configured callback concurrency is likely to bottleneck on the
 * HTTP connection pool rather than on CPU or queue capacity.
 *
 * <p>Deliberately a standalone component rather than folded into
 * {@code InMemoryQueueService} or {@code AsyncConfig}: its only job is
 * turning already-resolved, already-validated configuration into
 * operator-facing diagnostic output - a single responsibility that has
 * nothing to do with how the queues or the HTTP client actually work.
 *
 * <p>Runs on {@link ApplicationReadyEvent} rather than {@code @PostConstruct}
 * so it prints once, after the whole application context (including every
 * other component's own startup logging) has finished initializing - the
 * natural "last thing you see before traffic starts" summary, with no
 * dependency on bean-creation ordering.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QueueWorkerConfigReporter {

    private final ProviderProperties providerProperties;

    @EventListener(ApplicationReadyEvent.class)
    public void logStartupSummary() {
        ProviderProperties.Queue queue = providerProperties.getQueue();
        ProviderProperties.Scheduler scheduler = providerProperties.getScheduler();

        int incoming = queue.workersFor(QueueNames.INCOMING);
        int validation = queue.workersFor(QueueNames.VALIDATION);
        int processing = queue.workersFor(QueueNames.PROCESSING);
        int dlr = queue.workersFor(QueueNames.DLR);
        int callback = queue.workersFor(QueueNames.CALLBACK);
        int schedulerWorkers = scheduler.getWorkerCount();

        // One log call, not six - "a single summary" means the operator sees
        // one contiguous block, not six lines potentially interleaved with
        // other components' concurrent startup logging.
        String summary = """
                Queue Worker Configuration
                  Incoming    : %d
                  Validation  : %d
                  Processing  : %d
                  DLR         : %d
                  Callback    : %d
                  Scheduler   : %d"""
                .formatted(incoming, validation, processing, dlr, callback, schedulerWorkers);
        log.info(summary);

        warnIfCallbackWorkersExceedConnectionPool(callback);
    }

    /**
     * Pure predicate, deliberately separated from the logging call below
     * (and deliberately public) so it's directly unit-testable without
     * capturing log output - see QueueWorkerConfigReporterTest.
     */
    public boolean callbackWorkersExceedConnectionPool(int callbackWorkers, int maxConnectionsPerRoute) {
        return callbackWorkers > maxConnectionsPerRoute;
    }

    /**
     * Cross-checks CALLBACK's configured dispatcher-loop count against
     * operator.callback.max-connections-per-route: if more dispatch loops
     * can be attempting a webhook POST at once than the pool has connections
     * for one destination, the excess loops will simply queue behind the
     * pool rather than run in parallel - not a correctness problem (the
     * pooled HttpClient5 connection manager queues callers rather than
     * failing), just a likely-unintended concurrency ceiling lower than the
     * worker count implies. Warning only, never failing startup - an
     * operator may have deliberately set callback-workers higher than the
     * pool as harmless headroom, or may be about to raise the pool size
     * separately.
     */
    private void warnIfCallbackWorkersExceedConnectionPool(int callbackWorkers) {
        int maxConnectionsPerRoute = providerProperties.getCallback().getMaxConnectionsPerRoute();
        if (callbackWorkersExceedConnectionPool(callbackWorkers, maxConnectionsPerRoute)) {
            log.warn("Configured callback workers ({}) exceed HTTP connection pool size ({}).",
                    callbackWorkers, maxConnectionsPerRoute);
            log.warn("Some callback workers may wait for available HTTP connections.");
            log.warn("Consider increasing: operator.callback.max-connections-per-route, operator.callback.max-total-connections");
        }
    }
}
