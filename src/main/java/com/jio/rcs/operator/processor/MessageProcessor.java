package com.jio.rcs.operator.processor;

import com.jio.rcs.operator.config.ProviderProperties;
import com.jio.rcs.operator.dto.request.SendMessageRequest;
import com.jio.rcs.operator.mapper.MessageMapper;
import com.jio.rcs.operator.metrics.RuntimeMetricsRecorder;
import com.jio.rcs.operator.model.MessageContext;
import com.jio.rcs.operator.queue.QueueMessage;
import com.jio.rcs.operator.queue.QueueNames;
import com.jio.rcs.operator.queue.QueueService;
import com.jio.rcs.operator.registry.MessageStore;
import com.jio.rcs.operator.statemachine.MessageState;
import com.jio.rcs.operator.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * The "Message Engine" stage of the pipeline (Controller -&gt; Validation -&gt;
 * Message Engine -&gt; DLR Engine -&gt; Callback Engine). Places the message, as
 * ACCEPTED, into the in-memory MessageStore and hands it off to the
 * Incoming queue, then returns immediately - accept synchronously, process
 * asynchronously, exactly like a real provider edge API.
 *
 * <p>This is an open simulator: there's no per-client/per-provider routing
 * anymore, so every message is ingested against the single global
 * {@code operator.identity}/{@code operator.tps}/{@code operator.latency}/
 * {@code operator.dlr}/{@code operator.probability} configuration. Nothing
 * here touches a database; MessageStore is a bounded in-process map only.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MessageProcessor {

    private final MessageMapper messageMapper;
    private final MessageStore messageStore;
    private final QueueService queueService;
    private final ProviderProperties providerProperties;
    private final RuntimeMetricsRecorder metricsRecorder;

    public MessageContext ingest(SendMessageRequest request, String batchId) {
        String providerMessageId = IdGenerator.providerMessageId(providerProperties.getIdentity().getProviderCode());
        String callbackUrl = request.getCallbackUrl();

        MessageContext message = messageMapper.toContext(request, providerMessageId,
                MessageState.ACCEPTED.name(), callbackUrl);
        message.setBatchId(batchId);
        message.setProviderName(providerProperties.getIdentity().getProviderName());
        message.setInternalMessageId(IdGenerator.internalMessageId());
        messageStore.put(message);

        queueService.publish(QueueNames.INCOMING, QueueMessage.builder()
                .correlationId(providerMessageId)
                .payload(new IncomingTask(providerMessageId))
                .build());

        // Single canonical acceptance point (single-send and bulk both flow through
        // here), so this is the one place message-ingestion TPS is measured -
        // see RuntimeMetricsRecorder / GET /metrics' runtime.currentTps etc.
        metricsRecorder.recordMessageIngested();

        // DEBUG, not INFO: this fires once per accepted message, so at the
        // 10,000+ TPS this simulator targets it would otherwise be the
        // single biggest source of log volume. Default logging.level.com.jio.rcs.operator=INFO
        // keeps it silent; set it to DEBUG (application.properties) to see
        // per-message tracing again.
        log.debug("Accepted message {} for agentId={}", providerMessageId, request.getAgentId());
        return message;
    }
}
