package com.jio.rcs.operator.wire;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jio.rcs.operator.config.WireProviderProperties;
import com.jio.rcs.operator.util.IdGenerator;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Real Dotgo RBM wire contract - both the legacy bot/async API and the
 * newer agentMessages API {@code DotgoRcsProvider.php} can be configured to
 * use (see {@code shouldUseDotgoAgentMessagesApi()} - selected by the
 * configured base_url's host). Unlike Jio, Dotgo does not have the CPaaS
 * caller generate a messageId up front - the provider (us) mints one and
 * returns it in the send response, which the CPaaS then stores as its own
 * {@code external_message_id} (see {@code DotgoRcsProvider::makeApiRequest()}
 * / {@code normalizeDotgoAgentMessagesHttpResponse()}).
 *
 * <p>Every mapping is registered both un-prefixed ({@code /wire/dotgo/...} -
 * legacy, backward-compatible; DLRs use the single default
 * {@code operator.wire.profiles.dotgo.callback-url}) and with a leading
 * {@code /{instance}/wire/dotgo/...} segment (multi-instance routing - DLRs
 * go to {@code operator.instances.<instance>.profiles.dotgo.callback-url}
 * instead, resolved once at ingestion; see {@link CallbackUrlResolver}).
 */
@RestController
@RequiredArgsConstructor
@RequestMapping({"/wire/dotgo", "/{instance}/wire/dotgo"})
@Tag(name = "Wire format - Dotgo", description = "Real Dotgo RBM contract, both legacy bot/async and agentMessages APIs (see README 'Real provider wire format')")
public class DotgoWireController {

    private final WireIngestService wireIngestService;
    private final ObjectMapper objectMapper;
    private final WireProviderProperties wireProviderProperties;

    /** Legacy: POST {base_url}/rcs/bot/v1/{senderId}/messages/async, body {"messageContact":{"userContact":...},"ttl":...,"RCSMessage":{...}}. */
    @PostMapping("/rcs/bot/v1/{senderId}/messages/async")
    @Operation(summary = "Accept a message in Dotgo's legacy bot/async wire format")
    public ResponseEntity<ObjectNode> sendLegacy(@PathVariable(required = false) String instance,
                                                  @PathVariable String senderId, @RequestBody JsonNode body) {
        String to = body.path("messageContact").path("userContact").asText(null);
        JsonNode rcsMessage = body.has("RCSMessage") ? body.get("RCSMessage") : null;
        String msgId = IdGenerator.providerMessageId("SIM");

        wireIngestService.ingest(instance, "dotgo", to, inferLegacyType(rcsMessage), rcsMessage, msgId, Map.of("botId", senderId));

        ObjectNode rcsResponse = objectMapper.createObjectNode();
        rcsResponse.put("msgId", msgId);
        rcsResponse.put("status", "sent");
        ObjectNode response = objectMapper.createObjectNode();
        response.set("RCSMessage", rcsResponse);
        return ResponseEntity.ok(response);
    }

    /** New: POST {base_url}/rcs/v1/phones/{phone}/agentMessages/async?botId=..., body {"contentMessage":{...},"ttl":...}. */
    @PostMapping("/rcs/v1/phones/{phone}/agentMessages/async")
    @Operation(summary = "Accept a message in Dotgo's newer agentMessages wire format")
    public ResponseEntity<ObjectNode> sendAgentMessages(@PathVariable(required = false) String instance,
                                                         @PathVariable String phone,
                                                         @RequestParam("botId") String botId,
                                                         @RequestBody JsonNode body) {
        JsonNode contentMessage = body.has("contentMessage") ? body.get("contentMessage") : null;
        String msgId = IdGenerator.providerMessageId("SIM");

        wireIngestService.ingest(instance, "dotgo", phone, inferAgentMessagesType(contentMessage), contentMessage, msgId, Map.of("botId", botId));

        ObjectNode response = objectMapper.createObjectNode();
        response.put("messageId", msgId);
        return ResponseEntity.ok(response);
    }

    /** Simulated OAuth2 client-credentials token endpoint (real: POST {oauthHost}/auth/oauth/token, Basic client_id:client_secret). */
    @PostMapping("/auth/oauth/token")
    @Operation(summary = "Simulated Dotgo OAuth2 client-credentials token endpoint")
    public ResponseEntity<Map<String, Object>> token(@RequestParam(required = false) String grant_type) {
        return ResponseEntity.ok(OAuthTokenSupport.simulatedToken(wireProviderProperties.getStaticAccessToken()));
    }

    private String inferLegacyType(JsonNode rcsMessage) {
        if (rcsMessage == null) {
            return "text";
        }
        if (rcsMessage.has("templateMessage")) {
            return "template";
        }
        if (rcsMessage.has("richCard")) {
            return "rich_card";
        }
        if (rcsMessage.has("fileMessage")) {
            return "file";
        }
        return "text";
    }

    private String inferAgentMessagesType(JsonNode contentMessage) {
        if (contentMessage == null) {
            return "text";
        }
        if (contentMessage.has("templateMessage")) {
            return "template";
        }
        if (contentMessage.has("richCard")) {
            return "rich_card";
        }
        if (contentMessage.has("contentInfo")) {
            return "file";
        }
        return "text";
    }
}
