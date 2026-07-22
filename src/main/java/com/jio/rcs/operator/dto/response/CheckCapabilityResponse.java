package com.jio.rcs.operator.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** Response shape for POST /v1/capability/check. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CheckCapabilityResponse {
    private String phoneNumber;
    private boolean rcsCapable;
    private String agentId;
    private Instant checkedAt;
}
