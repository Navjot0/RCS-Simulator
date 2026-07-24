package com.jio.rcs.operator.unit.wire;

import com.jio.rcs.operator.model.MessageContext;
import com.jio.rcs.operator.statemachine.MessageState;
import com.jio.rcs.operator.wire.dlr.JioDlrFormatter;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Confirms JioDlrFormatter's output matches the shape
 * JioRcsWebhookProcessor.php's primary STATUS_EVENT branch expects -
 * entityType/entity.eventType/entity.messageId, not this simulator's own
 * self-designed CallbackEnvelope shape.
 */
class JioDlrFormatterTest {

    private final JioDlrFormatter formatter = new JioDlrFormatter();

    private MessageContext message(MessageState state) {
        MessageContext m = MessageContext.builder()
                .providerMessageId("jioABC123XYZ")
                .phoneNumber("+919999999999")
                .status(state.name())
                .wireAttributes(Map.of("botId", "assistant-42"))
                .build();
        m.setErrorCode("DEVICE_OFFLINE");
        m.setErrorDescription("Destination device is currently offline");
        return m;
    }

    @Test
    void profileIdIsJio() {
        assertThat(formatter.profileId()).isEqualTo("jio");
    }

    @Test
    void acceptedAndQueuedProduceNoDlr() {
        assertThat(formatter.build(message(MessageState.ACCEPTED), MessageState.ACCEPTED)).isEmpty();
        assertThat(formatter.build(message(MessageState.QUEUED), MessageState.QUEUED)).isEmpty();
    }

    @Test
    void submittedMapsToMessageSent() {
        Optional<Object> payload = formatter.build(message(MessageState.SUBMITTED), MessageState.SUBMITTED);
        assertThat(payload).isPresent();
        String json = payload.get().toString();
        assertThat(json).contains("entityType=STATUS_EVENT");
        assertThat(json).contains("eventType=MESSAGE_SENT");
        assertThat(json).contains("messageId=jioABC123XYZ");
        assertThat(json).contains("botId=assistant-42");
        assertThat(json).contains("userPhoneNumber=919999999999");
    }

    @Test
    void deliveredMapsToMessageDelivered() {
        Optional<Object> payload = formatter.build(message(MessageState.DELIVERED), MessageState.DELIVERED);
        assertThat(payload.get().toString()).contains("eventType=MESSAGE_DELIVERED");
    }

    @Test
    void displayedMapsToMessageRead() {
        Optional<Object> payload = formatter.build(message(MessageState.DISPLAYED), MessageState.DISPLAYED);
        assertThat(payload.get().toString()).contains("eventType=MESSAGE_READ");
    }

    @Test
    void failedIncludesErrorDetails() {
        Optional<Object> payload = formatter.build(message(MessageState.FAILED), MessageState.FAILED);
        String json = payload.get().toString();
        assertThat(json).contains("eventType=MESSAGE_FAILED");
        assertThat(json).contains("code=DEVICE_OFFLINE");
        assertThat(json).contains("message=Destination device is currently offline");
    }
}
