package com.jio.rcs.operator.metrics;

import com.jio.rcs.operator.config.ProviderProperties;
import com.jio.rcs.operator.queue.QueueNames;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.ThreadMXBean;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * Central, lock-free runtime metrics recorder backing GET /metrics'
 * {@code runtime} block (see {@link RuntimeMetrics}).
 *
 * <p>This is deliberately separate from the older {@code MetricsService},
 * which still answers per-lifecycle-state message counts and callback
 * totals by scanning the in-memory {@code MessageStore} - that scan is
 * bounded by {@code operator.message-store.retention-minutes} and only ever
 * runs when something polls GET /metrics or /statistics (not on the
 * message-ingestion hot path), so it was deliberately left as-is rather
 * than rewritten. Everything recorded here, by contrast, is updated at high
 * frequency from inside the hot path itself (once per accepted message,
 * once per queue dequeue, once per callback attempt), so every recording
 * method below is a {@link LongAdder}/{@link java.util.concurrent.atomic.AtomicLong}
 * update or a lock-free CAS retry loop - never a synchronized block, never
 * an iteration over the message store - and every recording call is a
 * no-op (single boolean check, immediately returns) when
 * {@code operator.metrics.enabled=false}.
 */
@Component
@RequiredArgsConstructor
public class RuntimeMetricsRecorder {

    private final ProviderProperties providerProperties;

    private final RollingTpsCounter ingestionTps = new RollingTpsCounter();

    private final Map<String, LatencyRecorder> queueWaitByStage = new ConcurrentHashMap<>();
    private final Map<String, LatencyRecorder> stageLatencyByStage = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> activeWorkersByStage = new ConcurrentHashMap<>();

    private final LongAdder callbackSuccessCount = new LongAdder();
    private final LongAdder callbackFailureCount = new LongAdder();
    private final LongAdder callbackRetryCount = new LongAdder();

    private final MemoryMXBean memoryMXBean = ManagementFactory.getMemoryMXBean();
    private final ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();

    /** Call once per message accepted into the pipeline (see MessageProcessor.ingest). Drives current/average/peak TPS. */
    public void recordMessageIngested() {
        if (!enabled()) {
            return;
        }
        ingestionTps.record();
    }

    /** Call once per dequeue, with how long the message sat in that queue before being picked up. */
    public void recordQueueWait(String queueName, long waitMillis) {
        if (!enabled()) {
            return;
        }
        queueWaitByStage.computeIfAbsent(queueName, n -> new LatencyRecorder()).record(waitMillis);
    }

    /** Call once per message handled by a stage, with how long that stage's listener took to process it. */
    public void recordStageLatency(String queueName, long latencyMillis) {
        if (!enabled()) {
            return;
        }
        stageLatencyByStage.computeIfAbsent(queueName, n -> new LatencyRecorder()).record(latencyMillis);
    }

    /** Call immediately before a dispatch loop invokes its listener, and {@link #workerFinished} immediately after - drives worker-utilization%. */
    public void workerStarted(String queueName) {
        if (!enabled()) {
            return;
        }
        activeWorkersByStage.computeIfAbsent(queueName, n -> new AtomicInteger()).incrementAndGet();
    }

    public void workerFinished(String queueName) {
        if (!enabled()) {
            return;
        }
        AtomicInteger active = activeWorkersByStage.get(queueName);
        if (active != null) {
            active.decrementAndGet();
        }
    }

    /** Call once per callback HTTP attempt (first attempt or retry), from CallbackEngine. */
    public void recordCallbackAttempt(boolean success, boolean isRetryAttempt) {
        if (!enabled()) {
            return;
        }
        if (success) {
            callbackSuccessCount.increment();
        } else {
            callbackFailureCount.increment();
        }
        if (isRetryAttempt) {
            callbackRetryCount.increment();
        }
    }

    private boolean enabled() {
        return providerProperties.getMetrics().isEnabled();
    }

    public RuntimeMetrics snapshot() {
        if (!enabled()) {
            return RuntimeMetrics.builder().enabled(false).build();
        }

        long attempted = callbackSuccessCount.sum() + callbackFailureCount.sum();
        double callbackSuccessRate = attempted == 0 ? 0.0 : (100.0 * callbackSuccessCount.sum() / attempted);

        MemoryUsage heap = memoryMXBean.getHeapMemoryUsage();
        List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
        long gcCollectionCount = gcBeans.stream().mapToLong(GarbageCollectorMXBean::getCollectionCount).sum();
        long gcCollectionTimeMillis = gcBeans.stream().mapToLong(GarbageCollectorMXBean::getCollectionTime).sum();

        return RuntimeMetrics.builder()
                .enabled(true)
                .currentTps(ingestionTps.currentTps())
                .averageTps(ingestionTps.averageTps())
                .peakTps(ingestionTps.peakTps())
                .queueWaitMillis(averagesOf(queueWaitByStage))
                .stageLatencyMillis(averagesOf(stageLatencyByStage))
                .workerUtilizationPercent(utilizationOf(activeWorkersByStage))
                .perStage(buildPerStageMetrics())
                .callbackSuccessCount(callbackSuccessCount.sum())
                .callbackFailureCount(callbackFailureCount.sum())
                .callbackRetryCount(callbackRetryCount.sum())
                .callbackSuccessRatePercent(callbackSuccessRate)
                .activeThreadCount(threadMXBean.getThreadCount())
                .heapUsedBytes(heap.getUsed())
                .heapMaxBytes(heap.getMax())
                .gcCollectionCount(gcCollectionCount)
                .gcCollectionTimeMillis(gcCollectionTimeMillis)
                .build();
    }

    private Map<String, Double> averagesOf(Map<String, LatencyRecorder> source) {
        Map<String, Double> result = new LinkedHashMap<>();
        source.forEach((queueName, recorder) -> result.put(queueName, recorder.averageMillis()));
        return result;
    }

    private Map<String, Double> utilizationOf(Map<String, AtomicInteger> source) {
        Map<String, Double> result = new LinkedHashMap<>();
        source.forEach((queueName, active) -> {
            int configuredWorkers = providerProperties.getQueue().workersFor(queueName);
            double pct = configuredWorkers <= 0 ? 0.0 : (100.0 * active.get() / configuredWorkers);
            result.put(queueName, pct);
        });
        return result;
    }

    /**
     * Builds one {@link QueueStageMetrics} entry per pipeline queue (see
     * {@link QueueNames#ALL}), always - not only for stages that have seen
     * traffic. Iterating the fixed queue-name list rather than one of the
     * lazily-populated maps above means configuredWorkers is visible
     * immediately at startup even before the first message reaches that
     * stage, with everything traffic-dependent simply reading as 0 until it
     * does. queueDepth is deliberately left at 0 here rather than read from
     * QueueService: injecting QueueService into this class would create a
     * circular Spring bean dependency (InMemoryQueueService already depends
     * on RuntimeMetricsRecorder to record per-dequeue metrics) - so
     * MetricsService, which already holds both beans independently, fills
     * queueDepth in afterward from QueueService.depths().
     */
    private Map<String, QueueStageMetrics> buildPerStageMetrics() {
        Map<String, QueueStageMetrics> result = new LinkedHashMap<>();
        for (String queueName : QueueNames.ALL) {
            int configuredWorkers = providerProperties.getQueue().workersFor(queueName);
            int activeWorkers = activeWorkersByStage.getOrDefault(queueName, ZERO_ACTIVE).get();
            LatencyRecorder queueWait = queueWaitByStage.get(queueName);
            LatencyRecorder stageLatency = stageLatencyByStage.get(queueName);

            result.put(queueName, QueueStageMetrics.builder()
                    .queueName(queueName)
                    .configuredWorkers(configuredWorkers)
                    .activeWorkers(activeWorkers)
                    .idleWorkers(Math.max(0, configuredWorkers - activeWorkers))
                    .queueDepth(0)
                    .messagesProcessed(stageLatency == null ? 0L : stageLatency.sampleCount())
                    .averageProcessingTimeMillis(stageLatency == null ? 0.0 : stageLatency.averageMillis())
                    .averageQueueWaitMillis(queueWait == null ? 0.0 : queueWait.averageMillis())
                    .build());
        }
        return result;
    }

    /** Shared read-only zero so buildPerStageMetrics() never has to allocate an AtomicInteger just to read "0". */
    private static final AtomicInteger ZERO_ACTIVE = new AtomicInteger(0);
}
