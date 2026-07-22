package com.jio.rcs.operator.callback;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * The "message" block of {@link CallbackEnvelope}. Dispatch events (QUEUED,
 * SUBMITTED) carry the wire content under {@code content}; delivery events
 * (DELIVERED, DISPLAYED, FAILED, ...) carry the identical shape under
 * {@code payload} instead - matching the CPaaS platform's own convention of
 * naming the field differently depending on event_type. Only one of the two
 * is ever populated per instance; the other is omitted from the JSON.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonPropertyOrder({"id", "type", "direction", "number", "agent_id", "campaign_id", "content", "payload"})
public class CallbackMessage {

    private String id;
    private String type;
    private String direction;
    private String number;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Map<String, Object> content;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Map<String, Object> payload;

    @JsonProperty("agent_id")
    private String agentId;

    @JsonProperty("campaign_id")
    private String campaignId;
}
