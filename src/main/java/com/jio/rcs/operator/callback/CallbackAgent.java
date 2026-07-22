package com.jio.rcs.operator.callback;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** The "agent" block of {@link CallbackEnvelope} - which client/agent and provider handled this message. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CallbackAgent {

    private String id;
    private String name;
    private String provider;

    @JsonProperty("provider_type")
    private String providerType;
}
