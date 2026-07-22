package com.jio.rcs.operator.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * This service's outbound-message contract, matching the real captured
 * Jio/DotGo provider request envelope - {@code agent_id}/{@code to}/
 * {@code message_type}/{@code content}/{@code corelation_id}/
 * {@code callback_url}.
 *
 * <p><b>Nothing here is mandatory.</b> Every field - including
 * {@code agent_id}, {@code to}, {@code message_type}, and {@code content} -
 * is optional and unvalidated. Earlier versions of this simulator required
 * these fields to be present (and, before that, required {@code content} to
 * match one of four hard-coded typed DTOs). That doesn't match how a real
 * provider edge behaves in practice - callers send all kinds of partial or
 * malformed-looking payloads, and the simulator's job is to accept and
 * relay them, not to gatekeep. A missing field is simply {@code null}
 * downstream (e.g. an absent {@code to} means no phone number is recorded,
 * an absent {@code content} is echoed back as {@code null} in the DLR
 * webhook) rather than a rejected request. The only way to get a `400` now
 * is a genuinely malformed JSON body (e.g. {@code to} sent as a bare string
 * instead of an array) - see {@code GlobalExceptionHandler}.
 *
 * <p>{@code content} is opaque {@link JsonNode} - any valid JSON object,
 * array, or scalar - and {@code message_type} is a plain string with no
 * fixed set of allowed values, not an enum. The simulator never inspects
 * what's inside {@code content}, and echoes it back verbatim in the
 * DLR/dispatch webhook body. See README "Content model" for the full
 * rationale and examples.
 *
 * <p><b>Single recipient only:</b> {@code to} is an array in the real wire
 * format, but this simulator currently only processes the first entry -
 * true multi-recipient fan-out (one DLR/providerMessageId per number) isn't
 * implemented. Extra entries beyond {@code to[0]} are silently ignored.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SendMessageRequest {

    @JsonProperty("agent_id")
    private String agentId;

    private List<String> to;

    @JsonProperty("message_type")
    private String messageType;

    /**
     * Opaque, caller-defined JSON - the simulator never validates or
     * branches on its internal shape, and never requires it to be present;
     * a missing {@code content} is simply {@code null} downstream and is
     * echoed back as {@code null} in the DLR/dispatch webhook body.
     */
    private JsonNode content;

    @JsonProperty("corelation_id")
    private String correlationId;

    @JsonProperty("callback_url")
    private String callbackUrl;

    @JsonIgnore
    public String getPhoneNumber() {
        return (to == null || to.isEmpty()) ? null : to.get(0);
    }
}
