package com.jio.rcs.operator.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class HealthResponse {
    private String status;
    /** operator.identity.provider-name - this simulator's single provider identity. */
    private String providerName;
    private String timestamp;
}
