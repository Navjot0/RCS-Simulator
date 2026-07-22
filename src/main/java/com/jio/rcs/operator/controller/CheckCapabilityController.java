package com.jio.rcs.operator.controller;

import com.jio.rcs.operator.dto.request.CheckCapabilityRequest;
import com.jio.rcs.operator.dto.response.CheckCapabilityResponse;
import com.jio.rcs.operator.service.CapabilityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * Lets a CPaaS adapter check whether a destination number is RCS-capable
 * before attempting to send. Since this simulator has no real subscriber
 * database, capability is simulated via a deterministic hash of the phone
 * number (see {@link CapabilityService}) so repeated checks against the
 * same number are stable for the life of the process.
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "Check Capability", description = "Check whether a destination number is RCS-capable")
public class CheckCapabilityController {

    private final CapabilityService capabilityService;

    @PostMapping("/v1/capability/check")
    @Operation(summary = "Check RCS capability for a destination number")
    public ResponseEntity<CheckCapabilityResponse> checkCapability(@Valid @RequestBody CheckCapabilityRequest request) {
        boolean capable = capabilityService.isRcsCapable(request.getPhoneNumber());
        CheckCapabilityResponse response = CheckCapabilityResponse.builder()
                .phoneNumber(request.getPhoneNumber())
                .rcsCapable(capable)
                .agentId(request.getAgentId())
                .checkedAt(Instant.now())
                .build();
        return ResponseEntity.ok(response);
    }
}
