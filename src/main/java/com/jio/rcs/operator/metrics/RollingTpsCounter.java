package com.jio.rcs.operator.metrics;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Lock-free, ~1-second-resolution TPS counter. Reuses the same bit-packed
 * "epoch-second in the high bits, count in the low bits" + CAS-retry
 * technique as {@link com.jio.rcs.operator.service.TpsLimiterService}, but
 * for *measuring* throughput rather than enforcing a ceiling - two
 * different jobs that happen to share the same lock-free primitive.
 *
 * <p>"Current TPS" reports the most recently <em>completed</em> one-second
 * window's count, published the instant a new second's first {@link #record()}
 * rolls the window over - not a truly instantaneous per-call rate. A
 * genuinely instantaneous rate would need far more bookkeeping (a real
 * sliding window over many sub-buckets) to be meaningful, and the
 * optimization brief this class exists for explicitly asks metrics to stay
 * lightweight. Up to ~1 second of staleness on a monitoring gauge is an
 * acceptable trade for adding zero contention to the message-ingestion hot
 * path {@link #record()} is called from.
 */
public final class RollingTpsCounter {

    private static final int COUNT_BITS = 22;
    private static final long COUNT_MASK = (1L << COUNT_BITS) - 1;

    private final AtomicLong windowState;
    private final AtomicLong lastCompletedWindowCount = new AtomicLong(0);
    private final AtomicLong peak = new AtomicLong(0);
    private final LongAdder lifetimeTotal = new LongAdder();
    private final long startEpochSecond = currentEpochSecond();

    public RollingTpsCounter() {
        this.windowState = new AtomicLong(pack(currentEpochSecond(), 0));
    }

    /** Records one event. O(1) amortized, lock-free, safe under any level of concurrent contention. */
    public void record() {
        lifetimeTotal.increment();
        long now = currentEpochSecond();
        while (true) {
            long current = windowState.get();
            long epoch = unpackEpoch(current);
            int count = unpackCount(current);

            if (epoch == now) {
                long next = pack(epoch, count + 1);
                if (windowState.compareAndSet(current, next)) {
                    return;
                }
            } else {
                // A new second has begun: publish the just-completed window's
                // count for currentTps()/peakTps() to see, then start a fresh
                // window at count=1 for this event.
                long next = pack(now, 1);
                if (windowState.compareAndSet(current, next)) {
                    lastCompletedWindowCount.set(count);
                    updatePeak(count);
                    return;
                }
            }
        }
    }

    /** Most recently completed second's event count, or 0 if nothing has been recorded for over a second. */
    public long currentTps() {
        long packed = windowState.get();
        long epoch = unpackEpoch(packed);
        long now = currentEpochSecond();
        if (now == epoch) {
            // Still inside the window that most recently received a record()
            // call - report the last fully completed second instead of a
            // partial, still-accumulating count.
            return lastCompletedWindowCount.get();
        }
        if (now - epoch == 1) {
            // The window sitting in state right now IS the most recently
            // completed second (nothing has rolled it over yet).
            return unpackCount(packed);
        }
        // No traffic for more than a second - genuinely idle.
        return 0;
    }

    public long peakTps() {
        return peak.get();
    }

    public double averageTps() {
        long elapsedSeconds = Math.max(1, currentEpochSecond() - startEpochSecond);
        return (double) lifetimeTotal.sum() / elapsedSeconds;
    }

    private void updatePeak(long completedCount) {
        while (true) {
            long currentPeak = peak.get();
            if (completedCount <= currentPeak) {
                return;
            }
            if (peak.compareAndSet(currentPeak, completedCount)) {
                return;
            }
        }
    }

    private static long currentEpochSecond() {
        return System.currentTimeMillis() / 1000;
    }

    private static long pack(long epochSecond, int count) {
        return (epochSecond << COUNT_BITS) | (count & COUNT_MASK);
    }

    private static long unpackEpoch(long packed) {
        return packed >>> COUNT_BITS;
    }

    private static int unpackCount(long packed) {
        return (int) (packed & COUNT_MASK);
    }
}
