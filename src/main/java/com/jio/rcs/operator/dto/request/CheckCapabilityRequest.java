package com.jio.rcs.operator.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Request shape for POST /v1/capability/check. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CheckCapabilityRequest {

    @NotBlank(message = "agentId is required")
    private String agentId;

    @NotBlank(message = "phoneNumber is required")
    @Pattern(regexp = "^\\+?[1-9][0-9]{7,14}$", message = "phoneNumber must be a valid MSISDN, e.g. +919999999999")
    private String phoneNumber;
}
