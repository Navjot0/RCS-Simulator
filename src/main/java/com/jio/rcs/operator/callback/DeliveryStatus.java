package com.jio.rcs.operator.callback;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

/** The "delivery_status" block nested inside {@link DeliveryInfo} - the raw provider-side status echo. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeliveryStatus {

    private String provider;

    @JsonProperty("updated_at")
    private Instant updatedAt;

    @JsonProperty("webhook_data")
    private Map<String, Object> webhookData;

    @JsonProperty("webhook_status")
    private String webhookStatus;
}
