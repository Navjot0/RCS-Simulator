package com.jio.rcs.operator.service;

import com.jio.rcs.operator.config.ProviderProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Simulates the finite throughput (TPS) of a real operator - a single
 * fixed-window counter shared by every request, since this simulator now
 * represents one generic provider rather than several independently-
 * throttled ones. Once the window budget (operator.tps.*) is exhausted,
 * callers should reject with 429 until the window rolls over.
 *
 * <p><b>Lock-free by design.</b> {@code tryAcquire()} sits directly on the
 * hot path of every single accepted request - at the 10,000+ TPS this
 * simulator targets, that's 10,000+ calls/second from potentially just as
 * many concurrent request-handling threads. The earlier implementation
 * guarded the whole read-check-increment sequence with a {@code synchronized}
 * method: every one of those threads had to acquire the same monitor, one at
 * a time, turning what should be a few nanoseconds of arithmetic into a
 * serialization point for the entire service - the request-admission
 * equivalent of a single-lane bridge in front of a highway. No amount of
 * downstream optimization (queues, scheduler, callbacks) matters if
 * admission itself is capped there.
 *
 * <p>Replaced with a single {@link AtomicLong} that packs both the window's
 * start-time epoch (upper 42 bits - milliseconds since epoch comfortably
 * fits until the year 2109) and the in-window count (lower 22 bits, good for
 * up to ~4.19M requests/window) into one machine word, updated via a
 * lock-free compare-and-swap retry loop. Multiple threads racing to roll the
 * window over is harmless: exactly one CAS wins, the rest simply retry
 * against the value the winner installed - there is never a lock, a park, or
 * a context switch involved, just a tight CPU-bound retry loop that in
 * practice succeeds on the first or second attempt even under heavy
 * contention.
 */
@Service
@RequiredArgsConstructor
public class TpsLimiterService {

    /** Count occupies the low 22 bits (max ~4.19M/window); window-start-millis occupies the rest. */
    private static final int COUNT_BITS = 22;
    private static final long COUNT_MASK = (1L << COUNT_BITS) - 1;

    private final ProviderProperties providerProperties;

    /** Packed (windowStartMillis << COUNT_BITS) | count. Starts at "no window yet" so the first call always rolls over. */
    private final AtomicLong state = new AtomicLong(pack(0L, 0));

    public boolean tryAcquire() {
        var tps = providerProperties.getTps();
        if (!tps.isEnabled()) {
            return true;
        }
        return doTryAcquire(tps.getWindowMillis(), tps.getLimit());
    }

    public int currentWindowCount() {
        return unpackCount(state.get());
    }

    private boolean doTryAcquire(long windowMillis, int limit) {
        long now = System.currentTimeMillis();
        while (true) {
            long current = state.get();
            long windowStart = unpackWindowStart(current);
            int count = unpackCount(current);

            boolean windowExpired = now - windowStart >= windowMillis;
            long effectiveWindowStart = windowExpired ? now : windowStart;
            int effectiveCount = windowExpired ? 0 : count;

            if (effectiveCount >= limit) {
                // Still record the rollover if one is due, so a burst of
                // over-limit requests doesn't wedge the window open forever -
                // but never grant the permit itself.
                if (windowExpired) {
                    state.compareAndSet(current, pack(effectiveWindowStart, 0));
                }
                return false;
            }

            long next = pack(effectiveWindowStart, effectiveCount + 1);
            if (state.compareAndSet(current, next)) {
                return true;
            }
            // CAS lost the race to another thread (or another core rolling
            // the window over concurrently) - loop and retry against
            // whatever value won. No lock, no wait; just re-read and retry.
        }
    }

    private static long pack(long windowStartMillis, int count) {
        return (windowStartMillis << COUNT_BITS) | (count & COUNT_MASK);
    }

    private static long unpackWindowStart(long packed) {
        return packed >>> COUNT_BITS;
    }

    private static int unpackCount(long packed) {
        return (int) (packed & COUNT_MASK);
    }
}
