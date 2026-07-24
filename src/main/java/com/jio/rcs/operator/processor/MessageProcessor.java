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

import java.util.Map;

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
        return doIngest(request, batchId, null, null, null);
    }

    /**
     * Ingestion entry point for a message received through one of the real
     * provider wire-format controllers (see com.jio.rcs.operator.wire),
     * rather than this simulator's own {@code POST /v1/messages} contract.
     *
     * @param providerProfile           which real provider's format this came through - "jio", "dotgo", "vi", "airtel", etc. - tags MessageContext so CallbackEngine picks the matching DlrFormatter.
     * @param overrideProviderMessageId when non-blank, used as-is instead of generating a new provider-style id. Some providers (Jio) supply their own message id at send time via a query param that must be echoed back verbatim in later DLRs for the CPaaS caller to correlate them; others (Dotgo/VI/Airtel) expect the provider (us) to mint one and return it in the synchronous response instead.
     * @param wireAttributes            provider-specific identifiers (e.g. botId/senderId) a DlrFormatter needs later to build a faithful DLR - see MessageContext.wireAttributes.
     */
    public MessageContext ingestWire(SendMessageRequest request, String providerProfile,
                                      String overrideProviderMessageId, Map<String, String> wireAttributes) {
        return doIngest(request, null, providerProfile, overrideProviderMessageId, wireAttributes);
    }

    private MessageContext doIngest(SendMessageRequest request, String batchId, String providerProfile,
                                     String overrideProviderMessageId, Map<String, String> wireAttributes) {
        String providerMessageId = (overrideProviderMessageId != null && !overrideProviderMessageId.isBlank())
                ? overrideProviderMessageId
                : IdGenerator.providerMessageId(providerProperties.getIdentity().getProviderCode());
        String callbackUrl = request.getCallbackUrl();

        MessageContext message = messageMapper.toContext(request, providerMessageId,
                MessageState.ACCEPTED.name(), callbackUrl);
        message.setBatchId(batchId);
        message.setProviderName(providerProperties.getIdentity().getProviderName());
        message.setInternalMessageId(IdGenerator.internalMessageId());
        message.setProviderProfile(providerProfile);
        message.setWireAttributes(wireAttributes);
        messageStore.put(message);

        queueService.publish(QueueNames.INCOMING, QueueMessage.builder()
                .correlationId(providerMessageId)
                .payload(new IncomingTask(providerMessageId))
                .build());

        // Single canonical acceptance point (single-send, bulk, and every wire
        // profile all flow through here), so this is the one place
        // message-ingestion TPS is measured - see RuntimeMetricsRecorder /
        // GET /metrics' runtime.currentTps etc.
        metricsRecorder.recordMessageIngested();

        // DEBUG, not INFO: this fires once per accepted message, so at the
        // 10,000+ TPS this simulator targets it would otherwise be the
        // single biggest source of log volume. Default logging.level.com.jio.rcs.operator=INFO
        // keeps it silent; set it to DEBUG (application.properties) to see
        // per-message tracing again.
        log.debug("Accepted message {} for agentId={} providerProfile={}", providerMessageId, request.getAgentId(), providerProfile);
        return message;
    }
}
