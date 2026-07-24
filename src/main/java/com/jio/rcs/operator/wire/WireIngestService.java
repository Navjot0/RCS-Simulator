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

    /**
     * @param providerProfile           "jio", "dotgo", "vi", "airtel", etc. - must match a registered DlrFormatter's profileId() and an operator.wire.profiles.&lt;name&gt; entry.
     * @param phoneNumber                destination number as received on the wire (with or without a leading '+' - not normalized here, each provider's real format varies).
     * @param messageType                a coarse label for GET /v1/messages/{id} introspection only; not part of any real provider's DLR payload, so its exact value has no correctness impact.
     * @param content                    the provider-specific content subtree to store as MessageContext.content (opaque, echoed back only by GET /v1/messages/{id} - no real provider's DLR includes original content).
     * @param overrideProviderMessageId  see {@link MessageProcessor#ingestWire}.
     * @param wireAttributes             see {@link MessageContext#getWireAttributes()}.
     */
    public MessageContext ingest(String providerProfile, String phoneNumber, String messageType, JsonNode content,
                                  String overrideProviderMessageId, Map<String, String> wireAttributes) {
        if (!wireProviderProperties.isEnabled(providerProfile)) {
            throw new WireProfileDisabledException(providerProfile);
        }
        if (!tpsLimiterService.tryAcquire()) {
            throw new RateLimitExceededException("Provider TPS limit exceeded; try again shortly");
        }

        SendMessageRequest request = SendMessageRequest.builder()
                .to(phoneNumber != null ? List.of(phoneNumber) : null)
                .messageType(messageType)
                .content(content)
                .build();

        return messageProcessor.ingestWire(request, providerProfile, overrideProviderMessageId, wireAttributes);
    }
}
