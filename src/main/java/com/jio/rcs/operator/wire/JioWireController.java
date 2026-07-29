package com.jio.rcs.operator.wire;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jio.rcs.operator.config.WireProviderProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * Real Jio Business Messaging wire contract, so a CPaaS's {@code jio}-type
 * {@code Provider} row can point {@code base_url} straight at this
 * simulator (e.g. {@code http://simulator-host:8080/wire/jio}) with no
 * code changes on the CPaaS side - see {@code JioRcsProvider.php}'s
 * {@code sendTextMessageInternal()}/{@code sendCarouselInternal()}, which
 * build exactly this path/query-param shape by concatenating the
 * configured base_url with a fixed suffix.
 *
 * <p>Critically, the CPaaS caller generates its own {@code messageId} and
 * passes it as a query parameter at send time (see
 * {@code JioRcsProvider::sendTextMessageInternal()}) rather than reading
 * one back from the response body - so this id, not one this simulator
 * would otherwise mint itself, must be echoed back verbatim in every later
 * DLR for the CPaaS to correlate it (see {@link com.jio.rcs.operator.wire.dlr.JioDlrFormatter}).
 *
 * <p>Every mapping is registered both un-prefixed ({@code /wire/jio/...} -
 * legacy, backward-compatible; DLRs use the single default
 * {@code operator.wire.profiles.jio.callback-url}) and with a leading
 * {@code /{instance}/wire/jio/...} segment (multi-instance routing - DLRs
 * go to {@code operator.instances.<instance>.profiles.jio.callback-url}
 * instead, resolved once at ingestion; see {@link CallbackUrlResolver}).
 * {@code instance} is captured only on the send endpoint, where it's needed
 * to resolve a callback destination - the token endpoint's behavior doesn't
 * depend on which CPaaS instance is asking, so the path variable is simply
 * left unbound there.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping({"/wire/jio", "/{instance}/wire/jio"})
@Tag(name = "Wire format - Jio", description = "Real Jio Business Messaging contract (see README 'Real provider wire format')")
public class JioWireController {

    private final WireIngestService wireIngestService;
    private final ObjectMapper objectMapper;
    private final WireProviderProperties wireProviderProperties;

    @PostMapping("/messaging/users/{to}/assistantMessages/async")
    @Operation(summary = "Accept a message in Jio's real assistantMessages wire format")
    public ResponseEntity<ObjectNode> send(@PathVariable(required = false) String instance,
                                            @PathVariable String to,
                                            @RequestParam("messageId") String messageId,
                                            @RequestParam(value = "assistantId", required = false) String assistantId,
                                            @RequestBody(required = false) JsonNode body) {
        JsonNode content = (body != null && body.has("content")) ? body.get("content") : body;
        Map<String, String> wireAttributes = new HashMap<>();
        if (assistantId != null) {
            wireAttributes.put("botId", assistantId);
        }

        wireIngestService.ingest(instance, "jio", to, inferMessageType(content), content, messageId, wireAttributes);

        ObjectNode response = objectMapper.createObjectNode();
        response.put("name", "messaging/users/" + to + "/assistantMessages/" + messageId);
        return ResponseEntity.ok(response);
    }

    /**
     * Simulated OAuth2 client-credentials token endpoint - real Jio uses a
     * GET with query params (see {@code JioRcsProvider::getAccessToken()}).
     * Any client_id/client_secret is accepted; no real credential exists to
     * check in a simulator.
     */
    @GetMapping("/v1/oauth/token")
    @Operation(summary = "Simulated Jio OAuth2 client-credentials token endpoint")
    public ResponseEntity<Map<String, Object>> token(@RequestParam(required = false) String grant_type,
                                                       @RequestParam(required = false) String client_id,
                                                       @RequestParam(required = false) String client_secret,
                                                       @RequestParam(required = false) String scope) {
        return ResponseEntity.ok(OAuthTokenSupport.simulatedToken(wireProviderProperties.getStaticAccessToken()));
    }

    private String inferMessageType(JsonNode content) {
        if (content == null) {
            return "text";
        }
        if (content.has("richCardDetails")) {
            return "carousel";
        }
        if (content.has("contentInfo")) {
            return "media";
        }
        return "text";
    }
}
