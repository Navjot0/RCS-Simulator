package com.jio.rcs.operator.metrics;

import com.jio.rcs.operator.config.ProviderProperties;
import com.jio.rcs.operator.queue.QueueService;
import com.jio.rcs.operator.registry.MessageStore;
import com.jio.rcs.operator.statemachine.MessageState;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Aggregates runtime metrics surfaced on GET /metrics: per-state message
 * counts, live queue depths and callback delivery/DLQ counters. Everything
 * is computed on the fly from the in-memory MessageStore and QueueService -
 * there is no metrics table.
 */
@Service
@RequiredArgsConstructor
public class MetricsService {

    private final MessageStore messageStore;
    private final QueueService queueService;
    private final ProviderProperties providerProperties;
    private final RuntimeMetricsRecorder runtimeMetricsRecorder;

    public MetricsSnapshot snapshot() {
        long callbacksAttempted = messageStore.all().stream()
                .mapToLong(m -> m.getCallbackAttempts().size())
                .sum();
        long callbacksDelivered = messageStore.all().stream()
                .filter(m -> "DELIVERED".equals(m.getCallbackStatus()))
                .count();
        long callbacksDeadLettered = messageStore.all().stream()
                .filter(m -> "DEAD_LETTERED".equals(m.getCallbackStatus()))
                .count();

        return MetricsSnapshot.builder()
                .totalAccepted(count(MessageState.ACCEPTED))
                .totalDelivered(count(MessageState.DELIVERED))
                .totalDisplayed(count(MessageState.DISPLAYED))
                .totalFailed(count(MessageState.FAILED))
                .totalRejected(count(MessageState.REJECTED))
                .totalExpired(count(MessageState.EXPIRED))
                .totalUnknown(count(MessageState.UNKNOWN))
                .queueDepths(queueService.depths())
                .callbacksAttempted(callbacksAttempted)
                .callbacksDelivered(callbacksDelivered)
                .callbacksDeadLettered(callbacksDeadLettered)
                .averageLatencyMillis(latencyMidpoint())
                .runtime(withLiveQueueDepths(runtimeMetricsRecorder.snapshot()))
                .build();
    }

    /**
     * Fills in each perStage entry's queueDepth from QueueService.depths(),
     * which MetricsService already holds as its own dependency - deliberately
     * done here rather than inside RuntimeMetricsRecorder itself, since that
     * class must not depend on QueueService (InMemoryQueueService already
     * depends on RuntimeMetricsRecorder, and a dependency the other way
     * round would be a circular Spring bean reference). See
     * RuntimeMetricsRecorder#buildPerStageMetrics for the same note from the
     * other side.
     */
    private RuntimeMetrics withLiveQueueDepths(RuntimeMetrics runtime) {
        if (runtime.getPerStage() == null) {
            return runtime;
        }
        Map<String, Integer> depths = queueService.depths();
        runtime.getPerStage().forEach((queueName, stageMetrics) ->
                stageMetrics.setQueueDepth(depths.getOrDefault(queueName, 0)));
        return runtime;
    }

    private long count(MessageState state) {
        return messageStore.byStatus(state.name()).size();
    }

    private double latencyMidpoint() {
        var latency = providerProperties.getLatency();
        return (latency.getMinMillis() + latency.getMaxMillis()) / 2.0;
    }
}
