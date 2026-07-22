package com.jio.rcs.operator.scheduler;

import com.jio.rcs.operator.config.ProviderProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A hashed/hierarchical timing wheel - the delayed-task scheduler backing
 * {@link DlrScheduler}, used for every DLR lifecycle transition and every
 * callback retry backoff.
 *
 * <h2>Why not {@code ScheduledThreadPoolExecutor} (what this replaces)?</h2>
 * <p>Spring's {@code ThreadPoolTaskScheduler} - what {@code DlrScheduler}
 * used before - wraps a {@link java.util.concurrent.ScheduledThreadPoolExecutor},
 * whose delay queue is a single binary heap ({@code DelayedWorkQueue})
 * protected by one {@code ReentrantLock}. Every call to {@code schedule()}
 * does an {@code O(log n)} heap insert under that lock; every task
 * completion or cancellation does another {@code O(log n)} removal under
 * the same lock. At the volumes this simulator targets - 10,000+ TPS, each
 * message scheduling 2-4 DLR transitions plus however many callback
 * retries - that's tens of thousands of operations per second all
 * serialized through one lock guarding one shared heap. This is precisely
 * the bottleneck named in the optimization brief ("scheduling one
 * ScheduledFuture per event does not scale") and it gets worse, not better,
 * as load increases, since heap depth (and so per-operation cost) grows
 * with the number of outstanding scheduled tasks.
 *
 * <h2>How a timing wheel fixes it</h2>
 * <p>Instead of one global ordered structure, time itself is discretized
 * into fixed-width "ticks" (operator.scheduler.tick-duration-millis,
 * default 100ms) arranged in a circular array of "buckets"
 * (operator.scheduler.wheel-size, default 512 - so one full revolution
 * spans ~51.2s by default). Scheduling a task is just: compute how many
 * ticks from now it's due, drop it in
 * {@code bucket[(currentTick + ticksFromNow) % wheelSize]}, and if the
 * delay is longer than one full revolution, remember how many additional
 * revolutions ("rounds") must pass first. That's an array-index
 * computation plus an append to one bucket's lock-free queue - genuinely
 * {@code O(1)}, with no shared lock at all: two threads scheduling tasks
 * that land in different buckets never contend, and even two landing in
 * the <em>same</em> bucket only contend on that one bucket's
 * {@link ConcurrentLinkedQueue}, not on every other pending task in the
 * system. A single dedicated "ticker" thread advances one bucket per tick,
 * decrementing "rounds" for entries not yet due and handing genuinely due
 * ones off to a small worker pool to actually run - the ticker itself never
 * runs task logic, so a slow or misbehaving task can never delay the wheel
 * advancing for everyone else. This is the same technique behind Netty's
 * {@code HashedWheelTimer} and Kafka's request-purgatory timer, both chosen
 * for exactly this workload shape: huge numbers of short, mostly-uncancelled
 * delayed callbacks.
 *
 * <p>Trade-off, stated plainly: resolution is bounded by the tick duration
 * (a task can fire up to one tick late, ~100ms by default) rather than
 * millisecond-exact. That's a good trade here - DLR delays are configured
 * in whole seconds and nothing in this simulator's business logic depends
 * on sub-100ms scheduling precision - in exchange for O(1) scheduling that
 * doesn't degrade as outstanding-task volume grows.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TimingWheelScheduler {

    private final ProviderProperties providerProperties;

    // volatile: written once by the main startup thread inside start(), read
    // afterward by both the ticker thread and every caller thread invoking
    // schedule() - safe-publication via @PostConstruct completing before any
    // request is served already guarantees visibility in practice, but
    // volatile makes that guarantee explicit rather than implicit.
    private volatile ConcurrentLinkedQueue<WheelEntry>[] wheel;
    private volatile int wheelSize;
    private volatile long tickDurationMillis;
    private final AtomicLong currentTick = new AtomicLong(0);
    private volatile long wheelStartMillis;

    private Thread tickerThread;
    private ExecutorService taskExecutor;
    private Semaphore concurrencyLimiter;
    private volatile boolean running = true;

    @SuppressWarnings("unchecked")
    @PostConstruct
    public void start() {
        var schedulerConfig = providerProperties.getScheduler();
        this.wheelSize = Math.max(8, schedulerConfig.getWheelSize());
        this.tickDurationMillis = Math.max(1, schedulerConfig.getTickDurationMillis());
        this.wheelStartMillis = System.currentTimeMillis();

        this.wheel = new ConcurrentLinkedQueue[wheelSize];
        for (int i = 0; i < wheelSize; i++) {
            wheel[i] = new ConcurrentLinkedQueue<>();
        }

        // Virtual threads have no meaningful "pool size" to bound (they're
        // created per task, not pooled) - operator.scheduler.worker-count is
        // honored instead as a cap on how many due tasks may run
        // concurrently at once, via this semaphore. This gives the same
        // predictable-concurrency knob a fixed platform-thread pool would,
        // without reintroducing a scarce-thread ceiling.
        int workers = Math.max(1, schedulerConfig.getWorkerCount());
        this.concurrencyLimiter = new Semaphore(workers);
        ThreadFactory taskThreadFactory = Thread.ofVirtual().name("scheduler-task-", 0).factory();
        this.taskExecutor = Executors.newThreadPerTaskExecutor(taskThreadFactory);

        this.tickerThread = Thread.ofPlatform().name("scheduler-ticker").start(this::tickLoop);

        log.info("Timing wheel scheduler started: wheelSize={} tickDurationMillis={} workerCount={} " +
                        "(one revolution spans {}ms)",
                wheelSize, tickDurationMillis, workers, (long) wheelSize * tickDurationMillis);
    }

    /**
     * Schedules {@code task} to run at (or shortly after - see class
     * Javadoc on tick resolution) the given instant. {@code O(1)}: no
     * shared lock, no heap reordering - just a bucket-index computation and
     * a lock-free queue append.
     */
    public void schedule(Instant when, Runnable task) {
        long delayMillis = Math.max(0, when.toEpochMilli() - System.currentTimeMillis());
        long delayTicks = delayMillis / tickDurationMillis;

        long targetTick = currentTick.get() + delayTicks;
        int bucketIndex = (int) (targetTick % wheelSize);
        long rounds = delayTicks / wheelSize;

        wheel[bucketIndex].add(new WheelEntry(task, rounds));
    }

    private void tickLoop() {
        while (running) {
            long tick = currentTick.get();
            int bucketIndex = (int) (tick % wheelSize);
            ConcurrentLinkedQueue<WheelEntry> bucket = wheel[bucketIndex];

            // Drain the bucket once per revolution; entries not yet due
            // (rounds > 0) are re-queued for the next time this bucket
            // comes around rather than executed now.
            int size = bucket.size();
            for (int i = 0; i < size; i++) {
                WheelEntry entry = bucket.poll();
                if (entry == null) {
                    break;
                }
                if (entry.rounds > 0) {
                    entry.rounds--;
                    bucket.add(entry);
                } else {
                    // Hand off to the worker pool immediately - the ticker
                    // thread must never run task logic itself, or a slow
                    // task would delay every other bucket behind it. Virtual
                    // threads are cheap to create, so acquiring the
                    // concurrency-limiting semaphore happens inside the
                    // submitted task (on its own virtual thread), not here -
                    // a full semaphore never blocks the ticker itself.
                    taskExecutor.submit(() -> runWithConcurrencyLimit(entry.task));
                }
            }

            currentTick.incrementAndGet();
            sleepUntilNextTick(tick);
        }
    }

    private void sleepUntilNextTick(long completedTick) {
        long nextTickAtMillis = wheelStartMillis + (completedTick + 1) * tickDurationMillis;
        long sleepMillis = nextTickAtMillis - System.currentTimeMillis();
        if (sleepMillis > 0) {
            try {
                Thread.sleep(sleepMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                running = false;
            }
        }
        // If sleepMillis <= 0, the wheel has fallen behind (tick processing
        // took longer than one tick's worth of time under extreme load) -
        // proceed immediately to the next tick without sleeping, so the
        // wheel catches back up rather than compounding a growing lag.
    }

    private void runWithConcurrencyLimit(Runnable task) {
        try {
            concurrencyLimiter.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        try {
            task.run();
        } catch (Exception e) {
            log.error("Scheduled task failed: {}", e.getMessage(), e);
        } finally {
            concurrencyLimiter.release();
        }
    }

    @PreDestroy
    public void stop() {
        running = false;
        if (tickerThread != null) {
            tickerThread.interrupt();
        }
        if (taskExecutor != null) {
            taskExecutor.shutdownNow();
        }
        log.info("Timing wheel scheduler stopped");
    }

    /** One scheduled entry sitting in a wheel bucket. */
    private static final class WheelEntry {
        private final Runnable task;
        private long rounds;

        private WheelEntry(Runnable task, long rounds) {
            this.task = task;
            this.rounds = rounds;
        }
    }
}
