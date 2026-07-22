package com.jio.rcs.operator.metrics;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Live, lock-free-recorded runtime figures, nested under
 * {@link MetricsSnapshot#getRuntime()}. Everything here is accumulated by
 * {@link RuntimeMetricsRecorder} at the moment each event happens (message
 * ingested, dequeued, stage completed, callback attempted) rather than
 * computed by scanning MessageStore on request - see that class's Javadoc.
 *
 * <p>Added as a new field on {@link MetricsSnapshot}, not a replacement -
 * every field that existed on MetricsSnapshot before this change is
 * untouched, so any existing consumer of GET /metrics keeps working
 * identically and can additively start reading this block too.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RuntimeMetrics {

    /** False when operator.metrics.enabled=false - every other field is left at its zero value in that case. */
    private boolean enabled;

    /** Message-ingestion throughput - see RollingTpsCounter for the ~1s-resolution methodology. */
    private long currentTps;
    private double averageTps;
    private long peakTps;

    /** Average time a message spent sitting in each queue before a dispatcher picked it up, keyed by queue name. */
    private Map<String, Double> queueWaitMillis;

    /** Average time each pipeline stage took to process one message once dequeued, keyed by queue name (the CALLBACK entry is effectively callback-delivery latency for a message's first attempt). */
    private Map<String, Double> stageLatencyMillis;

    /** Percentage of each stage's configured dispatcher-loop count that is busy processing a message right now, keyed by queue name. */
    private Map<String, Double> workerUtilizationPercent;

    private long callbackSuccessCount;
    private long callbackFailureCount;
    private long callbackRetryCount;
    private double callbackSuccessRatePercent;

    /** JVM-wide, read live from ThreadMXBean/MemoryMXBean/GarbageCollectorMXBean - not accumulated, always current as of the snapshot. */
    private int activeThreadCount;
    private long heapUsedBytes;
    private long heapMaxBytes;
    private long gcCollectionCount;
    private long gcCollectionTimeMillis;

    /**
     * One consolidated entry per pipeline stage (configured/active/idle
     * workers, queue depth, messages processed, average processing/queue-
     * wait time) - the single place to look when deciding whether a stage's
     * operator.queue.*-workers count needs tuning. Superset of - not a
     * replacement for - queueWaitMillis/stageLatencyMillis/
     * workerUtilizationPercent above, which are kept as-is for backward
     * compatibility with anything already reading them. Keyed by queue name
     * (see {@link com.jio.rcs.operator.queue.QueueNames}).
     */
    private Map<String, QueueStageMetrics> perStage;
}
