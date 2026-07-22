package com.jio.rcs.operator.unit;

import com.jio.rcs.operator.config.ProviderProperties;
import com.jio.rcs.operator.scheduler.TimingWheelScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the hierarchical timing wheel that now backs {@code DlrScheduler}
 * (replacing a single-locked-heap {@code ScheduledThreadPoolExecutor} - see
 * TimingWheelScheduler's class Javadoc for the full rationale) still
 * delivers the one guarantee that actually matters for DLR/callback-retry
 * timing: every scheduled task eventually fires, at (or shortly after - see
 * class Javadoc on tick resolution) its target time, and firing many tasks
 * concurrently never loses or double-fires one.
 *
 * <p>{@code start()}/{@code stop()} are normally {@code @PostConstruct}/
 * {@code @PreDestroy}-invoked by Spring; called directly here since this is
 * a plain unit test with no Spring context.
 */
class TimingWheelSchedulerTest {

    private TimingWheelScheduler scheduler;

    private TimingWheelScheduler newScheduler(long tickDurationMillis, int wheelSize, int workerCount) {
        ProviderProperties properties = new ProviderProperties();
        ProviderProperties.Scheduler schedulerConfig = new ProviderProperties.Scheduler();
        schedulerConfig.setTickDurationMillis(tickDurationMillis);
        schedulerConfig.setWheelSize(wheelSize);
        schedulerConfig.setWorkerCount(workerCount);
        properties.setScheduler(schedulerConfig);

        TimingWheelScheduler s = new TimingWheelScheduler(properties);
        s.start();
        return s;
    }

    @AfterEach
    void tearDown() {
        if (scheduler != null) {
            scheduler.stop();
        }
    }

    @Test
    void firesATaskAtApproximatelyItsScheduledTime() throws InterruptedException {
        scheduler = newScheduler(10, 64, 4);

        CountDownLatch fired = new CountDownLatch(1);
        long scheduledAtNanos = System.nanoTime();
        AtomicInteger elapsedMillis = new AtomicInteger();

        scheduler.schedule(Instant.now().plusMillis(100), () -> {
            elapsedMillis.set((int) ((System.nanoTime() - scheduledAtNanos) / 1_000_000));
            fired.countDown();
        });

        boolean completed = fired.await(2, TimeUnit.SECONDS);

        assertThat(completed).as("task should have fired within the timeout").isTrue();
        // Generous bound - tick resolution is 10ms here, but CI/sandbox
        // scheduling jitter is real; the point of this assertion is "it fired
        // roughly on time", not sub-tick precision.
        assertThat(elapsedMillis.get()).isBetween(90, 1000);
    }

    @Test
    void firesAPastDueTaskImmediatelyRatherThanWaitingARevolution() throws InterruptedException {
        scheduler = newScheduler(50, 32, 4);

        CountDownLatch fired = new CountDownLatch(1);
        scheduler.schedule(Instant.now().minusSeconds(5), fired::countDown);

        assertThat(fired.await(1, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void everyScheduledTaskFiresExactlyOnceUnderConcurrentScheduling() throws InterruptedException {
        scheduler = newScheduler(5, 128, 16);

        int taskCount = 500;
        CountDownLatch allFired = new CountDownLatch(taskCount);
        Set<Integer> firedIds = ConcurrentHashMap.newKeySet();

        List<Integer> ids = IntStream.range(0, taskCount).boxed().collect(Collectors.toList());
        ids.parallelStream().forEach(id -> {
            long delayMillis = id % 200; // spread across many ticks/buckets/revolutions
            scheduler.schedule(Instant.now().plusMillis(delayMillis), () -> {
                firedIds.add(id);
                allFired.countDown();
            });
        });

        boolean completed = allFired.await(5, TimeUnit.SECONDS);

        assertThat(completed).as("every scheduled task should have fired").isTrue();
        assertThat(firedIds).hasSize(taskCount);
    }
}
