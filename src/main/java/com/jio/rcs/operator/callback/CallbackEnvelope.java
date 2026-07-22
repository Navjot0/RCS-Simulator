package com.jio.rcs.operator.callback;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

/**
 * Top-level DLR/dispatch webhook envelope posted to the CPaaS callback URL.
 * This mirrors the CPaaS platform's own {@code message_dispatch} /
 * {@code message_delivery} event schema (captured from real Jio/Vi traffic)
 * rather than a self-designed shape, so the CPaaS adapter needs no
 * special-casing to consume simulator callbacks the same way it consumes
 * real provider callbacks. See {@link DlrWebhookMapping} for which internal
 * {@link com.jio.rcs.operator.statemachine.MessageState} produces which
 * {@code event_type}/{@code status} here.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonPropertyOrder({
        "event_type", "message_id", "external_message_id", "corelation_id",
        "rcs_message_id", "status", "timestamp", "delivery_info", "message",
        "agent", "additional_data"
})
public class CallbackEnvelope {

    @JsonProperty("event_type")
    private String eventType;

    /** Our own internal message id (a UUID) - see MessageContext.internalMessageId. */
    @JsonProperty("message_id")
    private String messageId;

    /** The provider's own message id - our providerMessageId. */
    @JsonProperty("external_message_id")
    private String externalMessageId;

    /** The correlationId the CPaaS caller supplied at send time, if any; null otherwise. */
    @JsonProperty("corelation_id")
    private String corelationId;

    /** Always null - this simulator never populates a separate GSMA RBM message id. */
    @JsonProperty("rcs_message_id")
    private String rcsMessageId;

    private String status;

    private Instant timestamp;

    private CallbackMessage message;

    private CallbackAgent agent;

    /** Present only for message_delivery events; omitted entirely for message_dispatch. */
    @JsonProperty("delivery_info")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private DeliveryInfo deliveryInfo;

    @JsonProperty("additional_data")
    private Map<String, Object> additionalData;
}
