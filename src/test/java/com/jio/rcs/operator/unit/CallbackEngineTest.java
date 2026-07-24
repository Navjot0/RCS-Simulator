package com.jio.rcs.operator.unit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.jio.rcs.operator.callback.CallbackClient;
import com.jio.rcs.operator.callback.CallbackContentMapper;
import com.jio.rcs.operator.callback.CallbackDeliveryResult;
import com.jio.rcs.operator.callback.CallbackEngine;
import com.jio.rcs.operator.config.ProviderProperties;
import com.jio.rcs.operator.config.WireProviderProperties;
import com.jio.rcs.operator.metrics.RuntimeMetricsRecorder;
import com.jio.rcs.operator.model.MessageContext;
import com.jio.rcs.operator.scheduler.DlrScheduler;
import com.jio.rcs.operator.statemachine.MessageState;
import com.jio.rcs.operator.wire.dlr.DlrFormatterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Verifies the callback retry + Dead Letter Queue behaviour, and that the
 * webhook body matches the CPaaS platform's own message_dispatch /
 * message_delivery event schema (see CallbackEnvelope / DlrWebhookMapping) -
 * not a self-designed shape. All state lives directly on MessageContext -
 * there is no separate callback store to inspect.
 */
class CallbackEngineTest {

    private CallbackClient callbackClient;
    private CallbackEngine callbackEngine;

    @BeforeEach
    void setUp() {
        callbackClient = mock(CallbackClient.class);
        DlrScheduler dlrScheduler = mock(DlrScheduler.class);

        // Run "scheduled" retries synchronously and immediately for the test.
        doAnswer(invocation -> {
            Runnable task = invocation.getArgument(1);
            task.run();
            return null;
        }).when(dlrScheduler).scheduleAt(any(), any());

        ProviderProperties properties = new ProviderProperties();
        ProviderProperties.Identity identity = new ProviderProperties.Identity();
        identity.setProviderName("SIM_RCS");
        identity.setProviderCode("SIM");
        identity.setProviderDisplayName("RCS Provider Simulator");
        properties.setIdentity(identity);

        ProviderProperties.Retry retry = new ProviderProperties.Retry();
        retry.setMaxAttempts(3);
        retry.setBackoffMillis(1);
        retry.setBackoffMultiplier(1.0);
        ProviderProperties.Callback callbackProps = new ProviderProperties.Callback();
        callbackProps.setRetry(retry);
        properties.setCallback(callbackProps);

        // Mirrors Spring Boot's autoconfigured ObjectMapper: registers
        // JavaTimeModule (via jackson-datatype-jsr310 on the classpath) so
        // java.time.Instant fields serialize correctly, and applies the same
        // spring.jackson.time-zone=Asia/Kolkata + write-dates-with-context-time-zone
        // settings from application.properties so Instant fields render in IST
        // (+05:30) here too, not just in the real running app.
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        objectMapper.setTimeZone(TimeZone.getTimeZone("Asia/Kolkata"));
        objectMapper.enable(SerializationFeature.WRITE_DATES_WITH_CONTEXT_TIME_ZONE);
        callbackEngine = new CallbackEngine(callbackClient, objectMapper, properties, dlrScheduler,
                new CallbackContentMapper(), new RuntimeMetricsRecorder(properties),
                new DlrFormatterRegistry(List.of()), new WireProviderProperties());
    }

    private MessageContext sampleMessage(MessageState state) {
        return MessageContext.builder()
                .providerMessageId("SIMTEST0002")
                .internalMessageId("11111111-2222-3333-4444-555555555555")
                .agentId("agent-demo-001")
                .phoneNumber("+919999999999")
                .messageType("TEXT")
                .status(state.name())
                .callbackUrl("https://cpaas.example.com/webhooks/jio-rcs")
                .acceptedAt(Instant.now())
                .build();
    }

    @Test
    void movesToDeadLetterQueueAfterExhaustingRetries() {
        when(callbackClient.post(any(), any()))
                .thenReturn(new CallbackDeliveryResult(false, 503, null, "connection refused"));

        MessageContext message = sampleMessage(MessageState.DELIVERED);
        callbackEngine.deliver(message);

        assertThat(message.getCallbackStatus()).isEqualTo("DEAD_LETTERED");
        assertThat(message.getCallbackAttempts()).hasSize(3);
        assertThat(message.getCallbackAttempts()).allMatch(a -> !a.isSuccess());
        verify(callbackClient, times(3)).post(any(), any());
    }

    @Test
    void marksDeliveredOnFirstSuccessfulAttempt() {
        when(callbackClient.post(any(), any()))
                .thenReturn(new CallbackDeliveryResult(true, 200, "ok", null));

        MessageContext message = sampleMessage(MessageState.DELIVERED);
        callbackEngine.deliver(message);

        assertThat(message.getCallbackStatus()).isEqualTo("DELIVERED");
        assertThat(message.getCallbackAttempts()).hasSize(1);
        assertThat(message.getCallbackAttempts().get(0).isSuccess()).isTrue();
        verify(callbackClient, times(1)).post(any(), any());
    }

    @Test
    void deliversOnSecondAttemptAfterOneFailure() {
        when(callbackClient.post(any(), any()))
                .thenReturn(new CallbackDeliveryResult(false, 500, null, "boom"))
                .thenReturn(new CallbackDeliveryResult(true, 200, "ok", null));

        MessageContext message = sampleMessage(MessageState.DELIVERED);
        callbackEngine.deliver(message);

        assertThat(message.getCallbackStatus()).isEqualTo("DELIVERED");
        assertThat(message.getCallbackAttempts()).hasSize(2);
        verify(callbackClient, times(2)).post(any(), any());
    }

    @Test
    void acceptedStateNeverFiresAWebhook() {
        MessageContext message = sampleMessage(MessageState.ACCEPTED);
        callbackEngine.deliver(message);

        verifyNoInteractions(callbackClient);
    }

    @Test
    void deliveredStateProducesMessageDeliveryEnvelopeWithDeliveryInfo() {
        when(callbackClient.post(any(), any()))
                .thenReturn(new CallbackDeliveryResult(true, 200, "ok", null));

        MessageContext message = sampleMessage(MessageState.DELIVERED);
        callbackEngine.deliver(message);

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(callbackClient).post(any(), jsonCaptor.capture());
        String json = jsonCaptor.getValue();

        assertThat(json).isNotEqualTo("{}");
        // Wire value is "delivery_report", not the more obvious "message_delivery" -
        // see DlrWebhookMapping.EventType.MESSAGE_DELIVERY's Javadoc for why:
        // the CPaaS's own JioRcsWebhookProcessor::mapNewFormatEventType() lookup
        // table never recognizes "message_delivery" as an event_type at all.
        assertThat(json).contains("\"event_type\":\"delivery_report\"");
        assertThat(json).contains("\"external_message_id\":\"SIMTEST0002\"");
        assertThat(json).contains("\"message_id\":\"11111111-2222-3333-4444-555555555555\"");
        assertThat(json).contains("\"status\":\"delivered\"");
        assertThat(json).contains("\"delivery_info\"");
        // Delivery events echo the wire content under message.payload, not message.content
        // (the inner "content" key inside that payload object is unaffected - see real captured
        // examples in the README, which show the exact same "payload": {"ttl":..., "content": {...}} shape).
        assertThat(json).contains("\"payload\":{\"ttl\"");
        assertThat(json).doesNotContain("\"content\":{\"ttl\"");
    }

    @Test
    void queuedStateProducesMessageDispatchEnvelopeWithoutDeliveryInfo() {
        when(callbackClient.post(any(), any()))
                .thenReturn(new CallbackDeliveryResult(true, 200, "ok", null));

        MessageContext message = sampleMessage(MessageState.QUEUED);
        callbackEngine.deliver(message);

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(callbackClient).post(any(), jsonCaptor.capture());
        String json = jsonCaptor.getValue();

        assertThat(json).contains("\"event_type\":\"message_dispatch\"");
        assertThat(json).contains("\"status\":\"submitted\"");
        // Dispatch events echo the wire content under message.content, not message.payload.
        assertThat(json).contains("\"content\":{\"ttl\"");
        assertThat(json).doesNotContain("\"payload\"");
        assertThat(json).doesNotContain("\"delivery_info\"");
    }

    @Test
    void deliveredStateProducesEnrichedWebhookDataShape() {
        when(callbackClient.post(any(), any()))
                .thenReturn(new CallbackDeliveryResult(true, 200, "ok", null));

        MessageContext message = sampleMessage(MessageState.DELIVERED);
        callbackEngine.deliver(message);

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(callbackClient).post(any(), jsonCaptor.capture());
        String json = jsonCaptor.getValue();

        // DELIVERED's raw event name pairs with FAILED's SEND_MESSAGE_FAILURE -
        // both describe the outcome of the same send attempt (see DlrWebhookMapping).
        assertThat(json).contains("\"webhook_status\":\"SEND_MESSAGE_SUCCESS\"");
        assertThat(json).contains("\"event_type\":\"SEND_MESSAGE_SUCCESS\"");
        // webhook_data is a nested botId/entity/entityType/userPhoneNumber shape,
        // not the old flat {messageId, eventType, senderPhoneNumber}.
        assertThat(json).contains("\"botId\":\"SIMULATOR-BOT-000000000001\"");
        assertThat(json).contains("\"entityType\":\"STATUS_EVENT\"");
        assertThat(json).contains("\"userPhoneNumber\":\"+919999999999\"");
        assertThat(json).contains("\"entity\":{\"eventId\"");
        assertThat(json).contains("\"senderPhoneNumber\":\"+919999999999\"");
    }

    @Test
    void timestampsRenderInIndianStandardTimeNotUtc() {
        when(callbackClient.post(any(), any()))
                .thenReturn(new CallbackDeliveryResult(true, 200, "ok", null));

        MessageContext message = sampleMessage(MessageState.DELIVERED);
        callbackEngine.deliver(message);

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(callbackClient).post(any(), jsonCaptor.capture());
        String json = jsonCaptor.getValue();

        // Every typed Instant field (envelope timestamp, delivery_info.*,
        // delivery_status.updated_at) is rendered with an explicit +05:30
        // offset via spring.jackson.time-zone=Asia/Kolkata, never trailing "Z".
        assertThat(json).doesNotContain("Z\"");
        assertThat(json).contains("+05:30");
        // entity.sendTime is hand-built (bypasses Jackson) via IstTime.format(),
        // so it needs its own explicit check that it also picked up IST.
        assertThat(json).containsPattern("\"sendTime\":\"[^\"]*\\+05:30\"");
    }

    @Test
    void deliveredEnvelopeFieldsAppearInTheDocumentedOrder() {
        when(callbackClient.post(any(), any()))
                .thenReturn(new CallbackDeliveryResult(true, 200, "ok", null));

        MessageContext message = sampleMessage(MessageState.DELIVERED);
        callbackEngine.deliver(message);

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(callbackClient).post(any(), jsonCaptor.capture());
        String json = jsonCaptor.getValue();

        // Top-level order is pinned via @JsonPropertyOrder on CallbackEnvelope
        // to match a real captured payload exactly, rather than whatever order
        // reflection happens to return - delivery_info comes before message/agent.
        assertInOrder(json, "\"event_type\"", "\"message_id\"", "\"external_message_id\"",
                "\"corelation_id\"", "\"rcs_message_id\"", "\"status\"", "\"timestamp\"",
                "\"delivery_info\"", "\"message\"", "\"agent\"", "\"additional_data\"");

        // message's own field order is also pinned via @JsonPropertyOrder on
        // CallbackMessage - payload/content comes last, after agent_id/campaign_id.
        assertInOrder(json, "\"id\"", "\"type\"", "\"direction\"", "\"number\"",
                "\"agent_id\"", "\"campaign_id\"", "\"payload\"");
    }

    /** Asserts each needle appears in json, and that each subsequent needle's first occurrence is later than the previous one's. */
    private void assertInOrder(String json, String... needlesInExpectedOrder) {
        int cursor = -1;
        for (String needle : needlesInExpectedOrder) {
            int index = json.indexOf(needle, cursor + 1);
            assertThat(index)
                    .as("expected %s to appear after position %d in: %s", needle, cursor, json)
                    .isGreaterThan(cursor);
            cursor = index;
        }
    }

    @Test
    void failedStateProducesFailureReasonFromErrorCodeAndDescription() {
        when(callbackClient.post(any(), any()))
                .thenReturn(new CallbackDeliveryResult(true, 200, "ok", null));

        MessageContext message = sampleMessage(MessageState.FAILED);
        message.applyTransition(MessageState.FAILED.name(), "DEVICE_OFFLINE", "Destination device is currently offline");
        callbackEngine.deliver(message);

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(callbackClient).post(any(), jsonCaptor.capture());
        String json = jsonCaptor.getValue();

        assertThat(json).contains("\"status\":\"failed\"");
        assertThat(json).contains("\"failure_reason\":\"Destination device is currently offline (Code: device_offline)\"");
    }
}
