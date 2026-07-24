package com.jio.rcs.operator.wire.dlr;

import com.jio.rcs.operator.model.MessageContext;
import com.jio.rcs.operator.statemachine.MessageState;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * DLR shape matching real Airtel RCS webhooks - {@code AirtelRcsWebhookProcessor::mapEventTypeToStatus()}
 * recognizes only DELIVERED, FAILED/INTERNAL_ERROR, and READ; there is no
 * SENT/queued webhook event at all on the real side, because Airtel's own
 * provider adapter reports the initial "sent" outcome synchronously from
 * the send API response body, not via a later webhook (see
 * {@code AirtelRcsProvider::sendTextMessage()}).
 *
 * <pre>{@code
 * {
 *   "messageId": "<providerMessageId>",
 *   "eventType": "DELIVERED" | "READ" | "FAILED",
 *   "sendTime": "2026-07-23T10:15:30Z",
 *   "agentId": "<Airtel agentId captured at send time>",
 *   "error": { "message": ..., "code": ... }   // FAILED only
 * }
 * }</pre>
 */
@Component
public class AirtelDlrFormatter implements DlrFormatter {

    @Override
    public String profileId() {
        return "airtel";
    }

    @Override
    public Optional<Object> build(MessageContext message, MessageState state) {
        String eventType = eventTypeFor(state);
        if (eventType == null) {
            // ACCEPTED/QUEUED/SUBMITTED: Airtel's real contract reports "sent"
            // synchronously in the send response, never via a webhook.
            return Optional.empty();
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("messageId", message.getProviderMessageId());
        payload.put("eventType", eventType);
        payload.put("sendTime", Instant.now().toString());

        String agentId = message.getWireAttributes() != null ? message.getWireAttributes().get("botId") : null;
        if (agentId != null) {
            payload.put("agentId", agentId);
        }

        if ("FAILED".equals(eventType)) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("message", message.getErrorDescription() != null
                    ? message.getErrorDescription() : "Message delivery failed");
            if (message.getErrorCode() != null) {
                error.put("code", message.getErrorCode());
            }
            payload.put("error", error);
        }

        return Optional.of(payload);
    }

    private String eventTypeFor(MessageState state) {
        return switch (state) {
            case DELIVERED -> "DELIVERED";
            case DISPLAYED -> "READ";
            case FAILED, REJECTED, EXPIRED, UNKNOWN -> "FAILED";
            default -> null;
        };
    }
}
