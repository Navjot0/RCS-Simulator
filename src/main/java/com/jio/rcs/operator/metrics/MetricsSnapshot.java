package com.jio.rcs.operator.metrics;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MetricsSnapshot {
    private long totalAccepted;
    private long totalDelivered;
    private long totalDisplayed;
    private long totalFailed;
    private long totalRejected;
    private long totalExpired;
    private long totalUnknown;
    private Map<String, Integer> queueDepths;
    private long callbacksAttempted;
    private long callbacksDelivered;
    private long callbacksDeadLettered;
    private double averageLatencyMillis;

    /**
     * Additive field: lock-free-recorded live figures (TPS, queue wait,
     * per-stage latency, worker utilization, callback success rate, JVM
     * memory/GC/thread stats) - see {@link RuntimeMetrics}. Everything above
     * this field is unchanged from before this was added.
     */
    private RuntimeMetrics runtime;
}
