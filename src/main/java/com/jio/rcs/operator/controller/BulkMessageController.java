package com.jio.rcs.operator.controller;

import com.jio.rcs.operator.dto.request.BulkMessageRequest;
import com.jio.rcs.operator.dto.response.BulkMessageResponse;
import com.jio.rcs.operator.exception.RateLimitExceededException;
import com.jio.rcs.operator.service.BulkMessageService;
import com.jio.rcs.operator.service.TpsLimiterService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Batch submission convenience: accepts a list of independent, potentially
 * different messages in one call, each validated and processed on its own
 * so one malformed entry doesn't fail the whole batch. Useful for QA/load
 * testing scenarios that need to fire many messages at once.
 */
@RestController
@RequestMapping("/v1/messages/bulk")
@RequiredArgsConstructor
@Tag(name = "Bulk Messages", description = "Accept a batch of RCS messages in a single call")
public class BulkMessageController {

    private final BulkMessageService bulkMessageService;
    private final TpsLimiterService tpsLimiterService;

    @PostMapping
    @Operation(summary = "Accept a batch of RCS messages for asynchronous processing")
    public ResponseEntity<BulkMessageResponse> sendBulk(@Valid @RequestBody BulkMessageRequest request) {
        if (!tpsLimiterService.tryAcquire()) {
            throw new RateLimitExceededException("Provider TPS limit exceeded; try again shortly");
        }
        return ResponseEntity.accepted().body(bulkMessageService.submit(request));
    }
}
