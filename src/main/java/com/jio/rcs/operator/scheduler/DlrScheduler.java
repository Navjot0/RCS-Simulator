package com.jio.rcs.operator.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Thin, deliberately stable facade in front of {@link TimingWheelScheduler},
 * used by the DLR engine to fire status transitions at their configured
 * delay (operator.dlr.delays-seconds) relative to message acceptance time,
 * and by the Callback Engine to schedule retry backoff. This is the
 * "scheduled job" mechanism that automatically generates DLRs and retries
 * callbacks without any external trigger.
 *
 * <p>This used to wrap Spring's {@code ThreadPoolTaskScheduler} directly
 * (a {@code ScheduledThreadPoolExecutor} under the hood - a single
 * lock-guarded heap that doesn't scale to this simulator's target DLR +
 * callback-retry volume). It's now backed by {@link TimingWheelScheduler}
 * instead - see that class's Javadoc for the full rationale. The public
 * {@link #scheduleAt(Instant, Runnable)} signature is unchanged, so
 * {@code DlrEngine} and {@code CallbackEngine} - the only two callers -
 * needed zero code changes for this swap.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DlrScheduler {

    private final TimingWheelScheduler timingWheelScheduler;

    public void scheduleAt(Instant when, Runnable task) {
        Instant fireAt = when.isBefore(Instant.now()) ? Instant.now() : when;
        timingWheelScheduler.schedule(fireAt, () -> {
            try {
                task.run();
            } catch (Exception e) {
                log.error("Scheduled DLR task failed: {}", e.getMessage(), e);
            }
        });
    }
}
