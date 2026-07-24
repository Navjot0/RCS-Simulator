package com.jio.rcs.operator.wire.dlr;

import com.jio.rcs.operator.model.MessageContext;
import com.jio.rcs.operator.statemachine.MessageState;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * DLR shape matching real VI RCS webhooks - the {@code event: "message_status"}
 * branch of {@code ViRcsWebhookProcessor::process()} /
 * {@code handleMessageStatusEvent()}, which reads {@code RCSMessage.msgId}
 * and {@code RCSMessage.status} (mapped via {@code mapViStatus()}: sent,
 * delivered, read, failed all recognized).
 *
 * <pre>{@code
 * {
 *   "event": "message_status",
 *   "RCSMessage": { "msgId": "<providerMessageId>", "status": "sent|delivered|read|failed", "timestamp": "..." },
 *   "messageContact": { "userContact": "<phone>" }
 * }
 * }</pre>
 */
@Component
public class ViDlrFormatter implements DlrFormatter {

    @Override
    public String profileId() {
        return "vi";
    }

    @Override
    public Optional<Object> build(MessageContext message, MessageState state) {
        String status = statusFor(state);
        if (status == null) {
            return Optional.empty();
        }

        Map<String, Object> rcsMessage = new LinkedHashMap<>();
        rcsMessage.put("msgId", message.getProviderMessageId());
        rcsMessage.put("status", status);
        rcsMessage.put("timestamp", Instant.now().toString());

        Map<String, Object> messageContact = new LinkedHashMap<>();
        messageContact.put("userContact", message.getPhoneNumber());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", "message_status");
        payload.put("RCSMessage", rcsMessage);
        payload.put("messageContact", messageContact);
        return Optional.of(payload);
    }

    private String statusFor(MessageState state) {
        return switch (state) {
            case SUBMITTED -> "sent";
            case DELIVERED -> "delivered";
            case DISPLAYED -> "read";
            case FAILED, REJECTED, EXPIRED, UNKNOWN -> "failed";
            default -> null;
        };
    }
}
