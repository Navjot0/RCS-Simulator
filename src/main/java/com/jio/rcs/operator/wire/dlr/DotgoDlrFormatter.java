package com.jio.rcs.operator.wire.dlr;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jio.rcs.operator.model.MessageContext;
import com.jio.rcs.operator.statemachine.MessageState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * DLR shape matching real Dotgo RBM webhooks - a Google Pub/Sub push
 * envelope whose {@code message.data} is base64(JSON), decoded by
 * {@code DotgoRcsWebhookProcessor::process()} into
 * {@code {messageId, senderPhoneNumber, eventType, sendTime, reason?, code?}}.
 * Only SENT/DELIVERED/READ/FAILED are recognized event types on the real
 * consumer side (see {@code mapDotgoEventTypeToInternalStatus()}); there is
 * no queued/submitted event.
 *
 * <pre>{@code
 * {
 *   "message": {
 *     "data": "<base64 JSON {messageId, senderPhoneNumber, eventType, sendTime, reason?, code?}>",
 *     "attributes": { "event_type": "SENT|DELIVERED|READ|FAILED", "business_id": "<botId>" }
 *   }
 * }
 * }</pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DotgoDlrFormatter implements DlrFormatter {

    private final ObjectMapper objectMapper;

    @Override
    public String profileId() {
        return "dotgo";
    }

    @Override
    public Optional<Object> build(MessageContext message, MessageState state) {
        String eventType = eventTypeFor(state);
        if (eventType == null) {
            return Optional.empty();
        }

        Map<String, Object> eventData = new LinkedHashMap<>();
        eventData.put("messageId", message.getProviderMessageId());
        eventData.put("senderPhoneNumber", stripPlus(message.getPhoneNumber()));
        eventData.put("eventType", eventType);
        eventData.put("sendTime", Instant.now().toString());
        if ("FAILED".equals(eventType)) {
            eventData.put("reason", message.getErrorDescription() != null
                    ? message.getErrorDescription() : "Dotgo reported message FAILED");
            if (message.getErrorCode() != null) {
                eventData.put("code", message.getErrorCode());
            }
        }

        String dataBase64;
        try {
            dataBase64 = Base64.getEncoder().encodeToString(objectMapper.writeValueAsBytes(eventData));
        } catch (Exception e) {
            log.error("Failed to encode Dotgo DLR message.data for message {}", message.getProviderMessageId(), e);
            return Optional.empty();
        }

        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("event_type", eventType);
        String botId = botId(message);
        if (botId != null) {
            attributes.put("business_id", botId);
        }

        Map<String, Object> messageWrapper = new LinkedHashMap<>();
        messageWrapper.put("data", dataBase64);
        messageWrapper.put("attributes", attributes);

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("message", messageWrapper);
        return Optional.of(envelope);
    }

    private String eventTypeFor(MessageState state) {
        return switch (state) {
            case SUBMITTED -> "SENT";
            case DELIVERED -> "DELIVERED";
            case DISPLAYED -> "READ";
            case FAILED, REJECTED, EXPIRED, UNKNOWN -> "FAILED";
            default -> null;
        };
    }

    private String botId(MessageContext message) {
        return message.getWireAttributes() != null ? message.getWireAttributes().get("botId") : null;
    }

    private String stripPlus(String phoneNumber) {
        if (phoneNumber == null) {
            return null;
        }
        return phoneNumber.startsWith("+") ? phoneNumber.substring(1) : phoneNumber;
    }
}
