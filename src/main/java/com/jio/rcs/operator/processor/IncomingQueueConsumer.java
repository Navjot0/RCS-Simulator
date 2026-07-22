package com.jio.rcs.operator.processor;

import com.jio.rcs.operator.config.ProviderProperties;
import com.jio.rcs.operator.queue.QueueMessage;
import com.jio.rcs.operator.queue.QueueNames;
import com.jio.rcs.operator.queue.QueueService;
import com.jio.rcs.operator.util.ProbabilityUtil;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * First pipeline stage: simulates provider ingestion latency (a single
 * global operator.latency range - see ProviderProperties) before handing
 * the message on to the Validation queue.
 *
 * <p>Under operator.performance-mode.enabled=true (see
 * ProviderProperties.PerformanceMode), operator.latency.min-millis/max-millis
 * is bypassed in favour of the performance-mode latency floor/ceiling
 * (both 0 by default, i.e. no artificial delay at all) - same
 * Thread.sleep()-based simulation, same code path, just a different
 * configured range, for stress/soak testing this simulator's own ceiling
 * rather than everyday integration testing where realistic pacing is
 * usually the point.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IncomingQueueConsumer {

    private final QueueService queueService;
    private final ProviderProperties providerProperties;

    @PostConstruct
    public void subscribe() {
        queueService.<IncomingTask>subscribe(QueueNames.INCOMING, message -> handle(message.getPayload()));
    }

    private void handle(IncomingTask task) throws InterruptedException {
        var performanceMode = providerProperties.getPerformanceMode();
        long minMillis;
        long maxMillis;
        if (performanceMode.isEnabled()) {
            minMillis = Math.max(0, performanceMode.getLatencyMinMillis());
            maxMillis = Math.max(minMillis, performanceMode.getLatencyMaxMillis());
        } else {
            var latency = providerProperties.getLatency();
            minMillis = latency.getMinMillis();
            maxMillis = latency.getMaxMillis();
        }
        if (maxMillis > 0) {
            Thread.sleep(ProbabilityUtil.randomBetween(minMillis, maxMillis));
        }

        queueService.publish(QueueNames.VALIDATION, QueueMessage.builder()
                .correlationId(task.providerMessageId())
                .payload(new ValidationTask(task.providerMessageId()))
                .build());
    }
}
