package com.jio.rcs.operator.callback;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jio.rcs.operator.config.ProviderProperties;
import com.jio.rcs.operator.metrics.RuntimeMetricsRecorder;
import com.jio.rcs.operator.model.CallbackAttempt;
import com.jio.rcs.operator.model.MessageContext;
import com.jio.rcs.operator.model.StatusHistoryEntry;
import com.jio.rcs.operator.scheduler.DlrScheduler;
import com.jio.rcs.operator.statemachine.MessageState;
import com.jio.rcs.operator.util.IstTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Delivers the webhook callback for a message's current status, with
 * automatic retry (operator.callback.retry.*) and Dead Letter Queue
 * semantics once retries are exhausted. All attempt bookkeeping lives on
 * the message's own in-memory MessageContext (callbackStatus /
 * callbackAttempts) - there is no separate callbacks table.
 *
 * <p>The webhook body matches the CPaaS platform's own message_dispatch /
 * message_delivery event schema (see {@link CallbackEnvelope} and
 * {@link DlrWebhookMapping}) rather than a self-designed shape, so no
 * adapter-side special-casing is needed downstream. ACCEPTED never reaches
 * here - it's reported only via the synchronous HTTP 202 response.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CallbackEngine {

    private static final Set<String> FAILURE_STATES = Set.of(
            MessageState.FAILED.name(), MessageState.REJECTED.name(),
            MessageState.EXPIRED.name(), MessageState.UNKNOWN.name());

    private final CallbackClient callbackClient;
    private final ObjectMapper objectMapper;
    private final ProviderProperties providerProperties;
    private final DlrScheduler dlrScheduler;
    private final CallbackContentMapper contentMapper;
    private final RuntimeMetricsRecorder metricsRecorder;

    public void deliver(MessageContext message) {
        MessageState state = MessageState.valueOf(message.getStatus());
        DlrWebhookMapping.Mapping mapping = DlrWebhookMapping.forState(state);

        if (mapping == null || mapping.eventType() == DlrWebhookMapping.EventType.NONE) {
            // ACCEPTED (and any unmapped state) is reported only via the
            // synchronous HTTP response - no webhook fires for it.
            return;
        }

        String callbackUrl = resolveCallbackUrl(message);
        if (callbackUrl == null || callbackUrl.isBlank()) {
            // DEBUG, not INFO - see MessageProcessor for why per-message
            // logging defaults to DEBUG in this simulator.
            log.debug("No callback URL configured; skipping webhook for message {}", message.getProviderMessageId());
            return;
        }

        CallbackEnvelope envelope = buildEnvelope(message, state, mapping);
        String json = writeJson(envelope);
        message.setCallbackStatus("PENDING");
        attempt(message, callbackUrl, json, 1);
    }

    private CallbackEnvelope buildEnvelope(MessageContext message, MessageState state,
                                            DlrWebhookMapping.Mapping mapping) {
        ProviderProperties.Identity identity = providerProperties.getIdentity();
        Instant now = Instant.now();

        String providerCodeLower = identity.getProviderCode() != null ? identity.getProviderCode().toLowerCase() : "unknown";
        String providerWireId = providerCodeLower + "-rcs";
        Map<String, Object> wireContent = contentMapper.toWireContent(message);

        CallbackMessage.CallbackMessageBuilder messageBuilder = CallbackMessage.builder()
                .id(message.getInternalMessageId())
                .type(wireMessageType(message.getMessageType()))
                .direction("outbound")
                .number(stripPlus(message.getPhoneNumber()))
                .agentId(message.getAgentId())
                .campaignId(message.getBatchId());

        boolean isDispatch = mapping.eventType() == DlrWebhookMapping.EventType.MESSAGE_DISPATCH;
        if (isDispatch) {
            messageBuilder.content(wireContent);
        } else {
            messageBuilder.payload(wireContent);
        }

        CallbackAgent agent = CallbackAgent.builder()
                .id(message.getAgentId())
                .name(message.getAgentId())
                .provider(identity.getProviderDisplayName() != null ? identity.getProviderDisplayName() : identity.getProviderName())
                .providerType(providerCodeLower)
                .build();

        CallbackEnvelope.CallbackEnvelopeBuilder envelope = CallbackEnvelope.builder()
                .eventType(mapping.eventType().wireValue())
                .messageId(message.getInternalMessageId())
                .externalMessageId(message.getProviderMessageId())
                .corelationId(message.getCorrelationId())
                .rcsMessageId(null)
                .status(mapping.status())
                .timestamp(now)
                .message(messageBuilder.build())
                .agent(agent);

        if (isDispatch) {
            envelope.additionalData(dispatchAdditionalData(providerWireId, providerCodeLower, message, mapping));
        } else {
            envelope.deliveryInfo(buildDeliveryInfo(message, providerWireId, mapping, now));
            envelope.additionalData(deliveryAdditionalData(providerWireId, message, mapping));
        }

        return envelope.build();
    }

    private Map<String, Object> dispatchAdditionalData(String providerWireId, String providerType,
                                                         MessageContext message, DlrWebhookMapping.Mapping mapping) {
        Map<String, Object> providerResponse = new LinkedHashMap<>();
        providerResponse.put("message_id", message.getProviderMessageId());
        providerResponse.put("status", mapping.status());

        Map<String, Object> additionalData = new LinkedHashMap<>();
        additionalData.put("provider", providerWireId);
        additionalData.put("provider_type", providerType);
        additionalData.put("provider_response", providerResponse);
        return additionalData;
    }

    private Map<String, Object> deliveryAdditionalData(String providerWireId, MessageContext message,
                                                         DlrWebhookMapping.Mapping mapping) {
        Map<String, Object> additionalData = new LinkedHashMap<>();
        additionalData.put("provider", providerWireId);
        additionalData.put("event_type", mapping.rawEventName());
        additionalData.put("webhook_data", genericWebhookData(message, mapping));
        additionalData.put("timestamp", null);
        additionalData.put("provider_code", message.getErrorCode() != null ? message.getErrorCode().toLowerCase() : null);
        // Note: real provider payloads also carry a short numeric err_code alongside provider_code
        // (e.g. "Error Code: 5"); this simulator doesn't maintain a numeric code per error taxonomy
        // entry, so this is always null - see README DLR-webhook section for this simplification.
        additionalData.put("err_code", null);
        return additionalData;
    }

    /**
     * Builds the {@code webhook_data} block shared by
     * {@code delivery_info.delivery_status.webhook_data} and
     * {@code additional_data.webhook_data} - a nested {@code botId}/
     * {@code entity}/{@code entityType}/{@code userPhoneNumber} shape that
     * mirrors a real captured provider payload, rather than a flatter
     * self-designed one. {@code entity} carries the raw provider-side event
     * details (a fresh eventId per webhook, the time the message was handed
     * to the network, the raw event name, the provider message id, and the
     * destination number).
     */
    private Map<String, Object> genericWebhookData(MessageContext message, DlrWebhookMapping.Mapping mapping) {
        Map<String, Object> entity = new LinkedHashMap<>();
        entity.put("eventId", UUID.randomUUID().toString());
        entity.put("sendTime", IstTime.format(resolveSendTime(message)));
        entity.put("eventType", mapping.rawEventName());
        entity.put("messageId", message.getProviderMessageId());
        entity.put("senderPhoneNumber", message.getPhoneNumber());

        Map<String, Object> webhookData = new LinkedHashMap<>();
        webhookData.put("botId", providerProperties.getIdentity().getBotId());
        webhookData.put("entity", entity);
        webhookData.put("entityType", "STATUS_EVENT");
        webhookData.put("userPhoneNumber", message.getPhoneNumber());
        return webhookData;
    }

    /**
     * The time the message was actually handed to the network - the same
     * moment {@code delivery_info.sent_at} reflects. Falls back to "now" for
     * states reached before SUBMITTED (or if that transition is somehow
     * missing from history).
     */
    private Instant resolveSendTime(MessageContext message) {
        Instant submittedAt = transitionTimeFor(message, MessageState.SUBMITTED.name());
        return submittedAt != null ? submittedAt : Instant.now();
    }

    private DeliveryInfo buildDeliveryInfo(MessageContext message, String providerWireId,
                                            DlrWebhookMapping.Mapping mapping, Instant now) {
        Instant sentAt = transitionTimeFor(message, MessageState.SUBMITTED.name());
        Instant deliveredAt = transitionTimeFor(message, MessageState.DELIVERED.name());
        Instant readAt = transitionTimeFor(message, MessageState.DISPLAYED.name());
        Instant failedAt = FAILURE_STATES.contains(message.getStatus()) ? now : null;

        DeliveryStatus deliveryStatus = DeliveryStatus.builder()
                .provider(providerWireId)
                .updatedAt(now)
                .webhookData(genericWebhookData(message, mapping))
                .webhookStatus(mapping.rawEventName())
                .build();

        return DeliveryInfo.builder()
                .attempts(0)
                .sentAt(sentAt)
                .deliveredAt(deliveredAt)
                .readAt(readAt)
                .failedAt(failedAt)
                .errorMessage(null)
                .failureReason(FAILURE_STATES.contains(message.getStatus()) ? buildFailureReason(message) : null)
                .deliveryStatus(deliveryStatus)
                .build();
    }

    private String buildFailureReason(MessageContext message) {
        String code = message.getErrorCode();
        String description = message.getErrorDescription();
        if (code == null && description == null) {
            return null;
        }
        if (code == null) {
            return description;
        }
        return (description != null ? description : "Delivery failed") + " (Code: " + code.toLowerCase() + ")";
    }

    private Instant transitionTimeFor(MessageContext message, String stateName) {
        return message.getHistory().stream()
                .filter(h -> stateName.equals(h.getNewStatus()))
                .map(StatusHistoryEntry::getTransitionAt)
                .reduce((first, second) -> second) // last matching transition, in case of retries
                .orElse(null);
    }

    /**
     * message_type is now a free-form string the caller controls (see
     * SendMessageRequest javadoc) rather than a fixed TEXT/RICH_CARD/
     * CAROUSEL/MEDIA enum, so there's nothing left to translate - it's
     * echoed straight into the webhook's message.type field, lower-cased
     * for consistency with the real captured wire values (e.g. "carousel",
     * "rich_message").
     */
    private String wireMessageType(String messageType) {
        return messageType == null || messageType.isBlank() ? "text" : messageType.toLowerCase();
    }

    private String stripPlus(String phoneNumber) {
        if (phoneNumber == null) {
            return null;
        }
        return phoneNumber.startsWith("+") ? phoneNumber.substring(1) : phoneNumber;
    }

    private void attempt(MessageContext message, String callbackUrl, String json, int attemptNumber) {
        CallbackDeliveryResult result = callbackClient.post(callbackUrl, json);
        // Lock-free counters backing runtime.callbackSuccessCount/-FailureCount/
        // -RetryCount/-SuccessRatePercent on GET /metrics - see RuntimeMetricsRecorder.
        // attemptNumber > 1 means this attempt itself is a retry (the first
        // attempt is never counted as one, matching operator.callback.retry's
        // own "attempt 1, then up to maxAttempts-1 retries" semantics).
        metricsRecorder.recordCallbackAttempt(result.isSuccess(), attemptNumber > 1);

        message.recordCallbackAttempt(CallbackAttempt.builder()
                .attemptNumber(attemptNumber)
                .httpStatusCode(result.getHttpStatusCode())
                .errorMessage(result.getErrorMessage())
                .success(result.isSuccess())
                .attemptedAt(Instant.now())
                .build());

        if (result.isSuccess()) {
            message.setCallbackStatus("DELIVERED");
            // DEBUG, not INFO - fires once per successful callback delivery
            // (the common case) - see MessageProcessor for why per-message
            // logging defaults to DEBUG in this simulator. DEAD_LETTERED
            // below stays at WARN since that's a genuine failure condition,
            // not routine traffic.
            log.debug("Callback for message {} delivered on attempt {}", message.getProviderMessageId(), attemptNumber);
            return;
        }

        int maxAttempts = providerProperties.getCallback().getRetry().getMaxAttempts();
        if (attemptNumber >= maxAttempts) {
            message.setCallbackStatus("DEAD_LETTERED");
            log.warn("Callback for message {} moved to Dead Letter Queue after {} attempts",
                    message.getProviderMessageId(), attemptNumber);
            return;
        }

        message.setCallbackStatus("RETRYING");

        var retry = providerProperties.getCallback().getRetry();
        long backoffMillis = (long) (retry.getBackoffMillis()
                * Math.pow(retry.getBackoffMultiplier(), attemptNumber - 1));
        backoffMillis = Math.min(backoffMillis, retry.getBackoffMaxMillis());

        Instant retryAt = Instant.now().plusMillis(backoffMillis);
        // DEBUG, not INFO - see MessageProcessor for why per-message logging
        // defaults to DEBUG in this simulator.
        log.debug("Scheduling callback retry #{} for message {} in {} ms",
                attemptNumber + 1, message.getProviderMessageId(), backoffMillis);
        dlrScheduler.scheduleAt(retryAt, () -> attempt(message, callbackUrl, json, attemptNumber + 1));
    }

    /**
     * A request can set its own {@code callbackUrl}; if it doesn't, every
     * DLR webhook for it falls back to the single global
     * operator.callback-url - there's no per-client registry to look one up
     * from anymore.
     */
    private String resolveCallbackUrl(MessageContext message) {
        if (message.getCallbackUrl() != null && !message.getCallbackUrl().isBlank()) {
            return message.getCallbackUrl();
        }
        return providerProperties.getCallbackUrl();
    }

    private String writeJson(CallbackEnvelope envelope) {
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (Exception e) {
            log.error("Failed to serialize callback payload", e);
            return "{}";
        }
    }
}
