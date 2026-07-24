package com.jio.rcs.operator.unit.wire;

import com.jio.rcs.operator.model.MessageContext;
import com.jio.rcs.operator.statemachine.MessageState;
import com.jio.rcs.operator.wire.dlr.ViDlrFormatter;
import org.junit.jupiter.api.Test;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Confirms ViDlrFormatter matches ViRcsWebhookProcessor.php's
 * event="message_status" / RCSMessage.msgId / RCSMessage.status branch.
 */
class ViDlrFormatterTest {

    private final ViDlrFormatter formatter = new ViDlrFormatter();

    private MessageContext message(MessageState state) {
        return MessageContext.builder()
                .providerMessageId("VIabc999")
                .phoneNumber("+919888888888")
                .status(state.name())
                .build();
    }

    @Test
    void profileIdIsVi() {
        assertThat(formatter.profileId()).isEqualTo("vi");
    }

    @Test
    void acceptedAndQueuedProduceNoDlr() {
        assertThat(formatter.build(message(MessageState.ACCEPTED), MessageState.ACCEPTED)).isEmpty();
        assertThat(formatter.build(message(MessageState.QUEUED), MessageState.QUEUED)).isEmpty();
    }

    @Test
    void submittedMapsToSentStatus() {
        Optional<Object> payload = formatter.build(message(MessageState.SUBMITTED), MessageState.SUBMITTED);
        String json = payload.get().toString();
        assertThat(json).contains("event=message_status");
        assertThat(json).contains("msgId=VIabc999");
        assertThat(json).contains("status=sent");
        assertThat(json).contains("userContact=+919888888888");
    }

    @Test
    void deliveredMapsToDeliveredStatus() {
        assertThat(formatter.build(message(MessageState.DELIVERED), MessageState.DELIVERED).get().toString())
                .contains("status=delivered");
    }

    @Test
    void displayedMapsToReadStatus() {
        assertThat(formatter.build(message(MessageState.DISPLAYED), MessageState.DISPLAYED).get().toString())
                .contains("status=read");
    }

    @Test
    void failedMapsToFailedStatus() {
        assertThat(formatter.build(message(MessageState.FAILED), MessageState.FAILED).get().toString())
                .contains("status=failed");
    }
}
