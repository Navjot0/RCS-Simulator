package com.jio.rcs.operator.wire;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jio.rcs.operator.util.IdGenerator;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Real Airtel RCS wire contract - {@code AirtelRcsProvider.php}'s
 * {@code postMessageSendToAirtel()} posts a flat JSON body (no nested
 * RCSMessage/content envelope, unlike Jio/Dotgo/VI) to
 * {@code {api_base_url}/conversation-message-acceptor/{api_version}/rcs/message/send}.
 * Airtel has no separate OAuth step - every request carries HTTP Basic
 * auth directly (see {@code bootstrapAgentContext()}), which this simulator
 * doesn't validate, same as every other wire profile.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/wire/airtel")
@Tag(name = "Wire format - Airtel", description = "Real Airtel RCS contract (see README 'Real provider wire format')")
public class AirtelWireController {

    private final WireIngestService wireIngestService;
    private final ObjectMapper objectMapper;

    @PostMapping("/conversation-message-acceptor/{version}/rcs/message/send")
    @Operation(summary = "Accept a message in Airtel's real message/send wire format")
    public ResponseEntity<ObjectNode> send(@PathVariable String version, @RequestBody JsonNode body) {
        String to = body.path("msisdn").asText(null);
        String agentId = body.path("agentId").asText(null);
        String msgId = IdGenerator.providerMessageId("SIM");

        wireIngestService.ingest("airtel", to, "template", body, msgId,
                agentId != null ? Map.of("botId", agentId) : Map.of());

        ObjectNode response = objectMapper.createObjectNode();
        response.put("success", true);
        response.put("status", "INITIATED");
        response.put("messageRequestId", msgId);
        return ResponseEntity.ok(response);
    }
}
