package com.jio.rcs.operator.controller;

import com.jio.rcs.operator.dto.request.SendMessageRequest;
import com.jio.rcs.operator.dto.response.MessageStatusResponse;
import com.jio.rcs.operator.dto.response.SendMessageResponse;
import com.jio.rcs.operator.exception.RateLimitExceededException;
import com.jio.rcs.operator.mapper.MessageMapper;
import com.jio.rcs.operator.processor.MessageProcessor;
import com.jio.rcs.operator.service.MessageQueryService;
import com.jio.rcs.operator.service.TpsLimiterService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@Tag(name = "Messages", description = "Send single RCS messages and query their provider status")
public class MessageController {

    private final MessageProcessor messageProcessor;
    private final MessageMapper messageMapper;
    private final MessageQueryService messageQueryService;
    private final TpsLimiterService tpsLimiterService;

    /**
     * This service's own provider-facing send endpoint, matching the real
     * captured Jio/DotGo provider request envelope (see README
     * "Content model"). Open/unauthenticated: accepts a message from any
     * caller with a free-form message_type and fully dynamic, opaque
     * content JSON - no fixed per-type schema, so any payload shape the
     * caller sends is accepted as-is. No field is mandatory either
     * (agent_id/to/message_type/content are all optional and unvalidated) -
     * the only way to get a 400 here is a genuinely malformed JSON body
     * (see GlobalExceptionHandler). Immediately hands back an ACCEPTED
     * response with a generated provider message id; all further lifecycle
     * progress is reported asynchronously via the DLR webhook, which
     * echoes the same content back verbatim.
     */
    @PostMapping("/v1/messages")
    @Operation(summary = "Accept a single RCS message for asynchronous processing")
    public ResponseEntity<SendMessageResponse> send(@Valid @RequestBody SendMessageRequest request) {
        if (!tpsLimiterService.tryAcquire()) {
            throw new RateLimitExceededException("Provider TPS limit exceeded; try again shortly");
        }
        var message = messageProcessor.ingest(request, null);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(messageMapper.toSendResponse(message));
    }

    /**
     * Synchronous polling convenience for local development/QA/automation.
     * Production CPaaS integrations are expected to rely on the DLR webhook
     * for status updates, not polling - this exists so a message's full
     * lifecycle can be inspected on demand during testing.
     */
    @GetMapping("/v1/messages/{providerMessageId}")
    @Operation(summary = "Get the latest provider status and full transition history for a message")
    public ResponseEntity<MessageStatusResponse> getStatus(@PathVariable String providerMessageId) {
        return ResponseEntity.ok(messageQueryService.getStatus(providerMessageId));
    }
}
