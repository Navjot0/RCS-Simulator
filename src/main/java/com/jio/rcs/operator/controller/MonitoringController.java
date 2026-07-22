package com.jio.rcs.operator.controller;

import com.jio.rcs.operator.config.ProviderProperties;
import com.jio.rcs.operator.dto.response.CallbackView;
import com.jio.rcs.operator.dto.response.HealthResponse;
import com.jio.rcs.operator.dto.response.PagedResponse;
import com.jio.rcs.operator.dto.response.QueueStatusResponse;
import com.jio.rcs.operator.dto.response.StatisticsResponse;
import com.jio.rcs.operator.metrics.MetricsService;
import com.jio.rcs.operator.metrics.MetricsSnapshot;
import com.jio.rcs.operator.model.MessageContext;
import com.jio.rcs.operator.queue.QueueService;
import com.jio.rcs.operator.registry.MessageStore;
import com.jio.rcs.operator.service.MessageQueryService;
import com.jio.rcs.operator.service.StatisticsService;
import com.jio.rcs.operator.service.TpsLimiterService;
import com.jio.rcs.operator.util.IstTime;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;

/**
 * Operational visibility endpoints: health, metrics, statistics, live queue
 * depths, and message/callback listings for debugging integration tests
 * against the simulator. Message/callback listings reflect only what is
 * currently held in the in-memory MessageStore.
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "Monitoring", description = "Health, metrics, statistics and queue/message/callback inspection")
public class MonitoringController {

    private final ProviderProperties providerProperties;
    private final MetricsService metricsService;
    private final StatisticsService statisticsService;
    private final QueueService queueService;
    private final TpsLimiterService tpsLimiterService;
    private final MessageQueryService messageQueryService;
    private final MessageStore messageStore;

    @GetMapping("/health")
    @Operation(summary = "Liveness check")
    public ResponseEntity<HealthResponse> health() {
        return ResponseEntity.ok(HealthResponse.builder()
                .status("UP")
                .providerName(providerProperties.getIdentity().getProviderName())
                .timestamp(IstTime.now())
                .build());
    }

    @GetMapping("/metrics")
    @Operation(summary = "Aggregated runtime metrics (per-state counts, queue depths, callback counters)")
    public ResponseEntity<MetricsSnapshot> metrics() {
        return ResponseEntity.ok(metricsService.snapshot());
    }

    @GetMapping("/statistics")
    @Operation(summary = "Business statistics: message counts by lifecycle status")
    public ResponseEntity<StatisticsResponse> statistics() {
        return ResponseEntity.ok(statisticsService.getStatistics());
    }

    @GetMapping("/queues")
    @Operation(summary = "Live depth of each internal pipeline queue, plus TPS status")
    public ResponseEntity<QueueStatusResponse> queues() {
        return ResponseEntity.ok(QueueStatusResponse.builder()
                .depths(queueService.depths())
                .workerThreads(providerProperties.getQueue().getWorkerThreads())
                .tpsLimit(providerProperties.getTps().getLimit())
                .currentWindowCount(tpsLimiterService.currentWindowCount())
                .build());
    }

    @GetMapping("/messages")
    @Operation(summary = "List in-memory messages, optionally filtered by status")
    public ResponseEntity<PagedResponse<MessageContext>> messages(@RequestParam(required = false) String status,
                                                                   @RequestParam(defaultValue = "0") int page,
                                                                   @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(messageQueryService.listMessages(status, page, size));
    }

    @GetMapping("/callbacks")
    @Operation(summary = "List callback delivery attempts for messages currently in memory, most recent first")
    public ResponseEntity<PagedResponse<CallbackView>> callbacks(@RequestParam(defaultValue = "0") int page,
                                                                  @RequestParam(defaultValue = "20") int size) {
        var views = messageStore.all().stream()
                .filter(m -> !m.getCallbackAttempts().isEmpty())
                .sorted(Comparator.comparing(MessageContext::getLastUpdatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .map(m -> CallbackView.builder()
                        .providerMessageId(m.getProviderMessageId())
                        .callbackUrl(m.getCallbackUrl())
                        .callbackStatus(m.getCallbackStatus())
                        .attempts(m.getCallbackAttempts())
                        .build())
                .toList();
        return ResponseEntity.ok(PagedResponse.of(views, page, size));
    }
}
