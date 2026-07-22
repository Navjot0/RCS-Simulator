package com.jio.rcs.operator.metrics;

import java.util.concurrent.atomic.LongAdder;

/**
 * Lock-free running average, backing every latency figure
 * {@link RuntimeMetricsRecorder} exposes (queue wait, per-stage processing
 * time). Two independent {@link LongAdder}s (sum, count) rather than one
 * synchronized accumulator: readers tolerate the sum and count reflecting
 * very slightly different instants (at worst, off by whatever samples land
 * in between the two reads), which is an acceptable imprecision for a
 * monitoring average and far cheaper under high concurrency than making the
 * pair atomic together.
 */
final class LatencyRecorder {

    private final LongAdder totalMillis = new LongAdder();
    private final LongAdder sampleCount = new LongAdder();

    void record(long millis) {
        totalMillis.add(Math.max(0, millis));
        sampleCount.increment();
    }

    double averageMillis() {
        long n = sampleCount.sum();
        return n == 0 ? 0.0 : (double) totalMillis.sum() / n;
    }

    /** How many samples have been recorded - doubles as "messages processed" when this recorder backs one pipeline stage. */
    long sampleCount() {
        return sampleCount.sum();
    }
}
