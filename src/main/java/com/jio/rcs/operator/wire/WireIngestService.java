package com.jio.rcs.operator.wire;

import com.fasterxml.jackson.databind.JsonNode;
import com.jio.rcs.operator.config.WireProviderProperties;
import com.jio.rcs.operator.dto.request.SendMessageRequest;
import com.jio.rcs.operator.exception.RateLimitExceededException;
import com.jio.rcs.operator.exception.WireProfileDisabledException;
import com.jio.rcs.operator.model.MessageContext;
import com.jio.rcs.operator.processor.MessageProcessor;
import com.jio.rcs.operator.service.TpsLimiterService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Shared glue between the real-provider wire controllers
 * (see this package) and the existing ingestion pipeline
 * ({@link MessageProcessor}, {@link TpsLimiterService}) - every wire
 * controller's send endpoint funnels through here rather than duplicating
 * the TPS-check / profile-enabled-check / SendMessageRequest-assembly logic
 * per provider.
 */
@Component
@RequiredArgsConstructor
public class WireIngestService {

    private final MessageProcessor messageProcessor;
    private final TpsLimiterService tpsLimiterService;
    private final WireProviderProperties wireProviderProperties;
    private final CallbackUrlResolver callbackUrlResolver;

    /**
     * Legacy, un-prefixed entry point ({@code /wire/{provider}/...}, no
     * {@code instance}) - unchanged behavior: DLR delivery falls back to the
     * single {@code operator.wire.profiles.<provider>.callback-url} default,
     * exactly as before multi-instance routing existed. Kept for backward
     * compatibility so nothing calling the old routes needs to change.
     *
     * @param providerProfile           "jio", "dotgo", "vi", "airtel", etc. - must match a registered DlrFormatter's profileId() and an operator.wire.profiles.&lt;name&gt; entry.
     * @param phoneNumber                destination number as received on the wire (with or without a leading '+' - not normalized here, each provider's real format varies).
     * @param messageType                a coarse label for GET /v1/messages/{id} introspection only; not part of any real provider's DLR payload, so its exact value has no correctness impact.
     * @param content                    the provider-specific content subtree to store as MessageContext.content (opaque, echoed back only by GET /v1/messages/{id} - no real provider's DLR includes original content).
     * @param overrideProviderMessageId  see {@link MessageProcessor#ingestWire(SendMessageRequest, String, String, Map)}.
     * @param wireAttributes             see {@link MessageContext#getWireAttributes()}.
     */
    public MessageContext ingest(String providerProfile, String phoneNumber, String messageType, JsonNode content,
                                  String overrideProviderMessageId, Map<String, String> wireAttributes) {
        return ingest(null, providerProfile, phoneNumber, messageType, content, overrideProviderMessageId, wireAttributes);
    }

    /**
     * Multi-instance entry point ({@code /{instance}/wire/{provider}/...}).
     * Resolves the DLR callback URL for {@code instance + providerProfile}
     * synchronously, before the message is ever placed in the async
     * pipeline (see {@link CallbackUrlResolver}) - an unknown instance or a
     * provider with no configured callback for that instance fails the
     * request immediately (propagates out of this method) rather than being
     * silently queued and only discovered when a DLR later fails to send.
     *
     * @param instance the {@code {instance}} path segment ("dev"/"staging"/"cerf"/...), or null/blank for the legacy un-prefixed route - see {@link #ingest(String, String, String, JsonNode, String, Map)}.
     */
    public MessageContext ingest(String instance, String providerProfile, String phoneNumber, String messageType,
                                  JsonNode content, String overrideProviderMessageId, Map<String, String> wireAttributes) {
        if (!wireProviderProperties.isEnabled(providerProfile)) {
            throw new WireProfileDisabledException(providerProfile);
        }

        // Resolved (and, on failure, thrown) before the TPS check and before
        // any queuing - a misconfigured instance shouldn't consume TPS
        // budget for a request that was always going to be rejected.
        String resolvedCallbackUrl = (instance != null && !instance.isBlank())
                ? callbackUrlResolver.resolve(instance, providerProfile)
                : null;

        if (!tpsLimiterService.tryAcquire()) {
            throw new RateLimitExceededException("Provider TPS limit exceeded; try again shortly");
        }

        SendMessageRequest request = SendMessageRequest.builder()
                .to(phoneNumber != null ? List.of(phoneNumber) : null)
                .messageType(messageType)
                .content(content)
                .build();

        return messageProcessor.ingestWire(request, providerProfile, overrideProviderMessageId, wireAttributes, resolvedCallbackUrl);
    }
}
