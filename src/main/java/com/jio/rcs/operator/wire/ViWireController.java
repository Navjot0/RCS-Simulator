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
 * Real VI RCS wire contract - {@code ViRcsProvider.php} builds the exact
 * same path shape and {@code messageContact}/{@code RCSMessage} body as
 * Dotgo's legacy bot/async API (both were originally the same VIRBM
 * platform), so this controller is structurally identical to
 * {@link DotgoWireController#sendLegacy} - kept as its own class/path
 * ({@code /wire/vi/...}) rather than merged, since a CPaaS deployment may
 * point distinct {@code Provider} rows (one {@code vi}, one {@code dotgo})
 * at the same simulator and needs distinguishable base_urls to tell them
 * apart, and because the two providers' DLR webhook formats (see
 * {@link com.jio.rcs.operator.wire.dlr.ViDlrFormatter} vs
 * {@link com.jio.rcs.operator.wire.dlr.DotgoDlrFormatter}) are unrelated.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/wire/vi")
@Tag(name = "Wire format - VI", description = "Real VI RCS contract (see README 'Real provider wire format')")
public class ViWireController {

    private final WireIngestService wireIngestService;
    private final ObjectMapper objectMapper;
    private final WireProviderProperties wireProviderProperties;

    @PostMapping("/rcs/bot/v1/{senderId}/messages/async")
    @Operation(summary = "Accept a message in VI's real bot/async wire format")
    public ResponseEntity<ObjectNode> send(@PathVariable String senderId, @RequestBody JsonNode body) {
        String to = body.path("messageContact").path("userContact").asText(null);
        JsonNode rcsMessage = body.has("RCSMessage") ? body.get("RCSMessage") : null;
        String msgId = IdGenerator.providerMessageId("VI");

        wireIngestService.ingest("vi", to, inferType(rcsMessage), rcsMessage, msgId, Map.of("botId", senderId));

        ObjectNode rcsResponse = objectMapper.createObjectNode();
        rcsResponse.put("msgId", msgId);
        rcsResponse.put("status", "sent");
        ObjectNode response = objectMapper.createObjectNode();
        response.set("RCSMessage", rcsResponse);
        return ResponseEntity.ok(response);
    }

    /** Simulated OAuth2 client-credentials token endpoint (real: POST {authUrl}/auth/oauth/token, Basic client_id:client_secret). */
    @PostMapping("/auth/oauth/token")
    @Operation(summary = "Simulated VI OAuth2 client-credentials token endpoint")
    public ResponseEntity<Map<String, Object>> token(@RequestParam(required = false) String grant_type) {
        return ResponseEntity.ok(OAuthTokenSupport.simulatedToken(wireProviderProperties.getStaticAccessToken()));
    }

    private String inferType(JsonNode rcsMessage) {
        if (rcsMessage == null) {
            return "text";
        }
        if (rcsMessage.has("templateMessage")) {
            return "template";
        }
        if (rcsMessage.has("richCard")) {
            return "rich_card";
        }
        if (rcsMessage.has("carousel")) {
            return "carousel";
        }
        if (rcsMessage.has("fileMessage")) {
            return "file";
        }
        return "text";
    }
}
