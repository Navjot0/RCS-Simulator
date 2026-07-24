package com.jio.rcs.operator.unit.wire;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jio.rcs.operator.model.MessageContext;
import com.jio.rcs.operator.statemachine.MessageState;
import com.jio.rcs.operator.wire.dlr.DotgoDlrFormatter;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Confirms DotgoDlrFormatter builds the Google Pub/Sub push envelope shape
 * DotgoRcsWebhookProcessor.php decodes ({@code message.data} = base64 JSON
 * with messageId/senderPhoneNumber/eventType/sendTime).
 */
class DotgoDlrFormatterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DotgoDlrFormatter formatter = new DotgoDlrFormatter(objectMapper);

    private MessageContext message(MessageState state) {
        return MessageContext.builder()
                .providerMessageId("DOTGOxyz789")
                .phoneNumber("919999999999")
                .status(state.name())
                .wireAttributes(Map.of("botId", "business-99"))
                .build();
    }

    @Test
    void profileIdIsDotgo() {
        assertThat(formatter.profileId()).isEqualTo("dotgo");
    }

    @Test
    void acceptedAndQueuedProduceNoDlr() {
        assertThat(formatter.build(message(MessageState.ACCEPTED), MessageState.ACCEPTED)).isEmpty();
        assertThat(formatter.build(message(MessageState.QUEUED), MessageState.QUEUED)).isEmpty();
    }

    @Test
    void deliveredDecodesToPubSubEnvelopeWithDeliveredEventType() throws Exception {
        Optional<Object> payload = formatter.build(message(MessageState.DELIVERED), MessageState.DELIVERED);
        assertThat(payload).isPresent();

        JsonNode root = objectMapper.valueToTree(payload.get());
        String base64Data = root.path("message").path("data").asText();
        JsonNode decoded = objectMapper.readTree(Base64.getDecoder().decode(base64Data));

        assertThat(decoded.get("messageId").asText()).isEqualTo("DOTGOxyz789");
        assertThat(decoded.get("senderPhoneNumber").asText()).isEqualTo("919999999999");
        assertThat(decoded.get("eventType").asText()).isEqualTo("DELIVERED");
        assertThat(root.path("message").path("attributes").path("event_type").asText()).isEqualTo("DELIVERED");
        assertThat(root.path("message").path("attributes").path("business_id").asText()).isEqualTo("business-99");
    }

    @Test
    void failedIncludesReason() throws Exception {
        MessageContext msg = message(MessageState.FAILED);
        msg.setErrorDescription("Destination device is currently offline");

        Optional<Object> payload = formatter.build(msg, MessageState.FAILED);
        JsonNode root = objectMapper.valueToTree(payload.get());
        String base64Data = root.path("message").path("data").asText();
        JsonNode decoded = objectMapper.readTree(Base64.getDecoder().decode(base64Data));

        assertThat(decoded.get("eventType").asText()).isEqualTo("FAILED");
        assertThat(decoded.get("reason").asText()).isEqualTo("Destination device is currently offline");
    }
}
