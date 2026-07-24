package com.jio.rcs.operator.wire.dlr;

import com.jio.rcs.operator.model.MessageContext;
import com.jio.rcs.operator.statemachine.MessageState;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * DLR shape matching real Jio Business Messaging webhooks - the
 * {@code entityType: "STATUS_EVENT"} / {@code entity.eventType} branch of
 * {@code JioRcsWebhookProcessor::process()} (its primary, most-exercised
 * format - see {@code processDlrEvent()} / {@code mapEventTypeToStatus()}
 * in that class), not the narrower "new webhook format"
 * ({@code event_type}/{@code status}/{@code message_id}) branch this
 * simulator's own self-designed {@code CallbackEnvelope} happens to also
 * produce.
 *
 * <pre>{@code
 * {
 *   "entityType": "STATUS_EVENT",
 *   "entity": {
 *     "eventType": "MESSAGE_SENT" | "MESSAGE_DELIVERED" | "MESSAGE_READ" | "MESSAGE_FAILED",
 *     "messageId": "<providerMessageId>",
 *     "sendTime": "2026-07-23T10:15:30Z",
 *     "error": { "code": ..., "errCode": null, "message": ... }   // MESSAGE_FAILED only
 *   },
 *   "botId": "<assistantId captured at send time>",
 *   "userPhoneNumber": "<phone, no leading +>"
 * }
 * }</pre>
 */
@Component
public class JioDlrFormatter implements DlrFormatter {

    @Override
    public String profileId() {
        return "jio";
    }

    @Override
    public Optional<Object> build(MessageContext message, MessageState state) {
        String eventType = eventTypeFor(state);
        if (eventType == null) {
            // ACCEPTED/QUEUED: Jio's real contract has no DLR event before
            // MESSAGE_SENT - only the synchronous accept response covers them.
            return Optional.empty();
        }

        Map<String, Object> entity = new LinkedHashMap<>();
        entity.put("eventType", eventType);
        entity.put("messageId", message.getProviderMessageId());
        entity.put("sendTime", Instant.now().toString());

        if ("MESSAGE_FAILED".equals(eventType)) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("code", message.getErrorCode());
            error.put("errCode", null);
            error.put("message", message.getErrorDescription() != null
                    ? message.getErrorDescription() : "Message delivery failed");
            entity.put("error", error);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("entityType", "STATUS_EVENT");
        payload.put("entity", entity);
        payload.put("botId", botId(message));
        payload.put("userPhoneNumber", stripPlus(message.getPhoneNumber()));
        return Optional.of(payload);
    }

    private String eventTypeFor(MessageState state) {
        return switch (state) {
            case SUBMITTED -> "MESSAGE_SENT";
            case DELIVERED -> "MESSAGE_DELIVERED";
            case DISPLAYED -> "MESSAGE_READ";
            case FAILED, REJECTED, EXPIRED, UNKNOWN -> "MESSAGE_FAILED";
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
