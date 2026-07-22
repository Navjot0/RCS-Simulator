package com.jio.rcs.operator.unit;

import com.jio.rcs.operator.config.ProviderProperties;
import com.jio.rcs.operator.service.TpsLimiterService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the lock-free, bit-packed-AtomicLong rewrite of TpsLimiterService
 * (replacing an earlier {@code synchronized} implementation - see that
 * class's Javadoc) still enforces the exact same admission contract: exactly
 * {@code limit} permits granted per window, disabled means unlimited, and -
 * the part a naive lock-free rewrite could most easily get wrong under
 * contention - no permit is lost or double-granted when many threads race
 * {@link TpsLimiterService#tryAcquire()} concurrently.
 */
class TpsLimiterServiceTest {

    private TpsLimiterService newLimiter(boolean enabled, int limit, long windowMillis) {
        ProviderProperties properties = new ProviderProperties();
        ProviderProperties.Tps tps = new ProviderProperties.Tps();
        tps.setEnabled(enabled);
        tps.setLimit(limit);
        tps.setWindowMillis(windowMillis);
        properties.setTps(tps);
        return new TpsLimiterService(properties);
    }

    @Test
    void grantsExactlyLimitPermitsWithinAWindow() {
        TpsLimiterService limiter = newLimiter(true, 3, 60_000);

        assertThat(limiter.tryAcquire()).isTrue();
        assertThat(limiter.tryAcquire()).isTrue();
        assertThat(limiter.tryAcquire()).isTrue();
        assertThat(limiter.tryAcquire()).isFalse();
        assertThat(limiter.tryAcquire()).isFalse();

        assertThat(limiter.currentWindowCount()).isEqualTo(3);
    }

    @Test
    void windowRollingOverResetsTheBudget() throws InterruptedException {
        TpsLimiterService limiter = newLimiter(true, 2, 100);

        assertThat(limiter.tryAcquire()).isTrue();
        assertThat(limiter.tryAcquire()).isTrue();
        assertThat(limiter.tryAcquire()).isFalse();

        Thread.sleep(150);

        assertThat(limiter.tryAcquire()).isTrue();
        assertThat(limiter.tryAcquire()).isTrue();
        assertThat(limiter.tryAcquire()).isFalse();
    }

    @Test
    void disabledLimiterNeverRejects() {
        TpsLimiterService limiter = newLimiter(false, 1, 60_000);

        for (int i = 0; i < 50; i++) {
            assertThat(limiter.tryAcquire()).isTrue();
        }
    }

    @Test
    void concurrentAcquiresNeverExceedOrUndercountTheLimit() throws Exception {
        int limit = 500;
        TpsLimiterService limiter = newLimiter(true, limit, 60_000);
        int threadCount = 32;
        int attemptsPerThread = 100; // 3200 total attempts racing for 500 permits

        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        try {
            List<Callable<Integer>> tasks = new java.util.ArrayList<>();
            for (int t = 0; t < threadCount; t++) {
                tasks.add(() -> {
                    int granted = 0;
                    for (int i = 0; i < attemptsPerThread; i++) {
                        if (limiter.tryAcquire()) {
                            granted++;
                        }
                    }
                    return granted;
                });
            }

            List<Future<Integer>> results = pool.invokeAll(tasks);
            AtomicInteger totalGranted = new AtomicInteger();
            for (Future<Integer> result : results) {
                totalGranted.addAndGet(result.get());
            }

            // Lock-free correctness under real contention: the total number of
            // "true" results across every thread must land exactly on the
            // configured limit - not one more (over-admission, a safety
            // violation) and not one less (a permit silently lost to a lost
            // CAS race).
            assertThat(totalGranted.get()).isEqualTo(limit);
            assertThat(limiter.currentWindowCount()).isEqualTo(limit);
        } finally {
            pool.shutdownNow();
        }
    }
}
