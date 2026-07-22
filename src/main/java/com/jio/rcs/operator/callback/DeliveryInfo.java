package com.jio.rcs.operator.callback;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** The "delivery_info" block of {@link CallbackEnvelope} - only present on message_delivery events. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeliveryInfo {

    private int attempts;

    @JsonProperty("sent_at")
    private Instant sentAt;

    @JsonProperty("delivered_at")
    private Instant deliveredAt;

    @JsonProperty("read_at")
    private Instant readAt;

    @JsonProperty("failed_at")
    private Instant failedAt;

    @JsonProperty("error_message")
    private String errorMessage;

    @JsonProperty("failure_reason")
    private String failureReason;

    @JsonProperty("delivery_status")
    private DeliveryStatus deliveryStatus;
}
