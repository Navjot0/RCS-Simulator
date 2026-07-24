package com.jio.rcs.operator.unit.wire;

import com.jio.rcs.operator.model.MessageContext;
import com.jio.rcs.operator.statemachine.MessageState;
import com.jio.rcs.operator.wire.dlr.AirtelDlrFormatter;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Confirms AirtelDlrFormatter matches AirtelRcsWebhookProcessor.php's
 * mapEventTypeToStatus() - only DELIVERED/READ/FAILED are real webhook
 * events; SENT is reported synchronously by the real provider, never via a
 * webhook, so ACCEPTED/QUEUED/SUBMITTED must all produce no DLR here.
 */
class AirtelDlrFormatterTest {

    private final AirtelDlrFormatter formatter = new AirtelDlrFormatter();

    private MessageContext message(MessageState state) {
        return MessageContext.builder()
                .providerMessageId("AIRTELqrs456")
                .phoneNumber("919777777777")
                .status(state.name())
                .wireAttributes(Map.of("botId", "agent-7"))
                .build();
    }

    @Test
    void profileIdIsAirtel() {
        assertThat(formatter.profileId()).isEqualTo("airtel");
    }

    @Test
    void acceptedQueuedAndSubmittedProduceNoDlr() {
        assertThat(formatter.build(message(MessageState.ACCEPTED), MessageState.ACCEPTED)).isEmpty();
        assertThat(formatter.build(message(MessageState.QUEUED), MessageState.QUEUED)).isEmpty();
        assertThat(formatter.build(message(MessageState.SUBMITTED), MessageState.SUBMITTED)).isEmpty();
    }

    @Test
    void deliveredProducesDeliveredEvent() {
        Optional<Object> payload = formatter.build(message(MessageState.DELIVERED), MessageState.DELIVERED);
        String json = payload.get().toString();
        assertThat(json).contains("messageId=AIRTELqrs456");
        assertThat(json).contains("eventType=DELIVERED");
        assertThat(json).contains("agentId=agent-7");
    }

    @Test
    void displayedProducesReadEvent() {
        assertThat(formatter.build(message(MessageState.DISPLAYED), MessageState.DISPLAYED).get().toString())
                .contains("eventType=READ");
    }

    @Test
    void failedIncludesErrorMessage() {
        MessageContext msg = message(MessageState.FAILED);
        msg.setErrorDescription("Destination device is currently offline");
        String json = formatter.build(msg, MessageState.FAILED).get().toString();
        assertThat(json).contains("eventType=FAILED");
        assertThat(json).contains("message=Destination device is currently offline");
    }
}
