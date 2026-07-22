package com.jio.rcs.operator.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jio.rcs.operator.dto.request.CheckCapabilityRequest;
import com.jio.rcs.operator.dto.request.SendMessageRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * End-to-end sanity check driving the real HTTP surface exactly like any
 * caller would: this simulator is open/unauthenticated (see README) - no
 * token, no registered client/agent - so every test here just sends a
 * request directly and confirms acceptance/validation behaves correctly.
 *
 * <p>POST /v1/messages accepts a fully dynamic {@code content} JSON (see
 * {@link SendMessageRequest}) - there's no per-message_type schema or
 * structural validation, so tests here exercise several unrelated content
 * shapes (including ones the simulator has never been told about) to prove
 * genuine dynamism, rather than one test per hard-coded message type.
 * Nothing is mandatory either - agent_id/to/message_type/content are all
 * optional - so most of what used to be "rejects ... (400)" tests are now
 * "accepts ... (202)" tests; the only remaining 400 path is genuinely
 * malformed JSON.
 */
@SpringBootTest
@AutoConfigureMockMvc
class MessageFlowIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private JsonNode json(String raw) throws Exception {
        return objectMapper.readTree(raw);
    }

    @Test
    void healthEndpointReflectsTheSingleConfiguredProviderIdentity() throws Exception {
        // Confirms operator.identity.provider-name from application.properties
        // is what this open, single-behaviour simulator reports itself as.
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.providerName").value("RCS_SIMULATOR"));
    }

    @Test
    void acceptsMessageFromAnyAgentWithNoAuthentication() throws Exception {
        SendMessageRequest request = SendMessageRequest.builder()
                .agentId("any-agent-whatsoever")
                .to(List.of("+919999999999"))
                .messageType("text")
                .content(json("{\"text\": \"hello, no token needed\"}"))
                .build();

        // Deliberately no Authorization header - this simulator accepts any caller.
        mockMvc.perform(post("/v1/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.providerMessageId", org.hamcrest.Matchers.startsWith("SIM")));
    }

    @Test
    void acceptsValidTextMessageAndReturnsProviderMessageId() throws Exception {
        SendMessageRequest request = SendMessageRequest.builder()
                .agentId("agent-demo-001")
                .to(List.of("+919999999999"))
                .messageType("text")
                .content(json("{\"text\": \"hello from CPaaS\"}"))
                .correlationId("corr-12345")
                .build();

        String body = mockMvc.perform(post("/v1/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.providerMessageId").value(org.hamcrest.Matchers.startsWith("SIM")))
                .andReturn().getResponse().getContentAsString();

        String providerMessageId = objectMapper.readTree(body).get("providerMessageId").asText();

        mockMvc.perform(get("/v1/messages/{id}", providerMessageId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.providerMessageId").value(providerMessageId));
    }

    @Test
    void acceptsAnInvalidLookingPhoneNumberSinceNothingIsValidatedAnymore() throws Exception {
        // Earlier versions of this API pattern-validated MSISDNs and
        // rejected anything that didn't look like a real phone number; that
        // constraint has been removed - "to" is never validated, so garbage
        // like "not-a-phone" is accepted exactly like a real number.
        SendMessageRequest request = SendMessageRequest.builder()
                .agentId("agent-demo-001")
                .to(List.of("not-a-phone"))
                .messageType("text")
                .content(json("{\"text\": \"hello\"}"))
                .build();

        mockMvc.perform(post("/v1/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted());
    }

    @Test
    void malformedJsonReturns400NotA500() throws Exception {
        // "to" must be a JSON array, not a bare string - this used to fall
        // through to the generic exception handler and return an unhelpful
        // 500 (HttpMessageNotReadableException wasn't explicitly mapped);
        // now it's a clear 400 VALIDATION_FAILED instead.
        String malformedBody = """
                {
                  "agent_id": "agent-demo-001",
                  "to": "919999999999",
                  "message_type": "text",
                  "content": { "text": "hello" }
                }
                """;

        mockMvc.perform(post("/v1/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(malformedBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));
    }

    @Test
    void acceptsTheExactRealCarouselPayloadReportedByTheUser() throws Exception {
        // Regression guard for the real Jio/DotGo carousel payload that
        // originally 500'd against the old self-designed schema.
        String realPayload = """
                {
                  "to": ["917015571043"],
                  "content": {
                    "carouselList": [
                      {
                        "mediaUrl": "https://i.ibb.co/RpKG7yWG/1440x480.jpg",
                        "cardTitle": "Card 1",
                        "suggestions": [],
                        "cardDescription": "This is Dot Go Template Carousel."
                      },
                      {
                        "mediaUrl": "https://i.ibb.co/RpKG7yWG/1440x480.jpg",
                        "cardTitle": "Card 2",
                        "suggestions": [],
                        "cardDescription": "This is the Card Description."
                      }
                    ]
                  },
                  "agent_id": "agent3232",
                  "message_type": "carousel",
                  "corelation_id": "corelation_id_carousel_dotgo"
                }
                """;

        mockMvc.perform(post("/v1/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(realPayload))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.correlationId").value("corelation_id_carousel_dotgo"))
                .andExpect(jsonPath("$.providerMessageId", org.hamcrest.Matchers.startsWith("SIM")));
    }

    @Test
    void acceptsMessageMissingContent() throws Exception {
        // content is no longer mandatory either - an absent content is
        // simply null downstream (and echoed as null in the DLR webhook).
        SendMessageRequest request = SendMessageRequest.builder()
                .agentId("agent-demo-001")
                .to(List.of("+919999999999"))
                .messageType("text")
                .build(); // no content at all

        mockMvc.perform(post("/v1/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted());
    }

    @Test
    void acceptsMessageMissingMessageType() throws Exception {
        String body = """
                {
                  "agent_id": "agent-demo-001",
                  "to": ["+919999999999"],
                  "content": { "text": "no message_type field at all" }
                }
                """;

        mockMvc.perform(post("/v1/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted());
    }

    @Test
    void acceptsACompletelyEmptyMessageBody() throws Exception {
        // The strongest demonstration that nothing is mandatory: an empty
        // JSON object - no agent_id, to, message_type, or content at all -
        // is still accepted and gets a real providerMessageId. The only way
        // to get a 400 from this endpoint now is a genuinely malformed JSON
        // body (see malformedJsonReturns400NotA500).
        mockMvc.perform(post("/v1/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.providerMessageId", org.hamcrest.Matchers.startsWith("SIM")));
    }

    @Test
    void acceptsRichCardShapedContent() throws Exception {
        // No RichCardDto/CardContentDto involved anymore - message_type is a
        // free string and content is opaque JSON, so this is just one more
        // shape among many, not a specially-known one.
        String body = """
                {
                  "agent_id": "agent-demo-001",
                  "to": ["+919999999999"],
                  "message_type": "rich_card",
                  "corelation_id": "corr-richcard",
                  "content": {
                    "cardTitle": "Summer Sale",
                    "cardDescription": "Up to 50% off",
                    "mediaUrl": "https://example.com/banner.png",
                    "suggestions": [
                      { "type": "OPEN_URL", "displayText": "Shop now", "openUrlAction": { "url": "https://example.com/sale" } }
                    ]
                  }
                }
                """;

        mockMvc.perform(post("/v1/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted());
    }

    @Test
    void acceptsMediaShapedContentWithCalendarSuggestion() throws Exception {
        String body = """
                {
                  "agent_id": "agent-demo-001",
                  "to": ["+919999999999"],
                  "message_type": "media",
                  "corelation_id": "corr-media",
                  "content": {
                    "mediaUrl": "https://example.com/doc.pdf",
                    "caption": "Check out our new product document",
                    "suggestions": [
                      { "type": "CREATE_CALENDAR_EVENT", "displayText": "Add to calendar",
                        "calendarEventAction": { "title": "Product Launch", "startTime": "2026-08-01T10:00:00", "endTime": "2026-08-01T11:00:00" } }
                    ]
                  }
                }
                """;

        mockMvc.perform(post("/v1/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted());
    }

    @Test
    void acceptsAnEntirelyMadeUpContentShapeNeverContemplatedByTheSimulator() throws Exception {
        // The whole point of the dynamic-content redesign: message_type
        // "poll" and this options/allowMultiple shape were never defined
        // anywhere in this codebase, yet it's accepted like any other
        // payload - proving the simulator really doesn't know every payload
        // structure in advance.
        String body = """
                {
                  "agent_id": "agent-demo-001",
                  "to": ["+919999999999"],
                  "message_type": "poll",
                  "corelation_id": "corr-poll-novel-shape",
                  "content": {
                    "question": "Which flavor do you prefer?",
                    "options": ["Vanilla", "Chocolate", "Strawberry"],
                    "allowMultiple": false,
                    "metadata": { "campaign": "summer-2026", "priority": 3 }
                  }
                }
                """;

        mockMvc.perform(post("/v1/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.correlationId").value("corr-poll-novel-shape"));
    }

    @Test
    void acceptsContentEvenWhenItDoesNotMatchMessageTypeSemantically() throws Exception {
        // Deliberate trade-off from the dynamic-content redesign: content is
        // never validated against message_type, so a "text" message with a
        // carousel-shaped content body (or any other mismatch) is still
        // accepted - the simulator relays whatever was sent, exactly like
        // the real provider does.
        String body = """
                {
                  "agent_id": "agent-demo-001",
                  "to": ["+919999999999"],
                  "message_type": "text",
                  "content": { "carouselList": [ { "cardTitle": "Not actually text" } ] }
                }
                """;

        mockMvc.perform(post("/v1/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted());
    }

    @Test
    void checkCapabilityReturnsDeterministicResultForSameNumber() throws Exception {
        CheckCapabilityRequest request = CheckCapabilityRequest.builder()
                .agentId("agent-demo-001")
                .phoneNumber("+919999999999")
                .build();

        String firstBody = mockMvc.perform(post("/v1/capability/check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phoneNumber").value("+919999999999"))
                .andReturn().getResponse().getContentAsString();

        boolean firstResult = objectMapper.readTree(firstBody).get("rcsCapable").asBoolean();

        String secondBody = mockMvc.perform(post("/v1/capability/check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        boolean secondResult = objectMapper.readTree(secondBody).get("rcsCapable").asBoolean();

        org.assertj.core.api.Assertions.assertThat(firstResult).isEqualTo(secondResult);
    }

    @Test
    void uploadFileRejectsUnsupportedContentType() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "notes.txt", "text/plain", "hello world".getBytes());

        mockMvc.perform(multipart("/v1/media")
                        .file(file))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void uploadFileThenFetchesItBackViaPublicMediaLink() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "pixel.png", "image/png", new byte[]{1, 2, 3, 4});

        String body = mockMvc.perform(multipart("/v1/media")
                        .file(file))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.mediaId").value(org.hamcrest.Matchers.startsWith("MEDIA")))
                .andReturn().getResponse().getContentAsString();

        String mediaId = objectMapper.readTree(body).get("mediaId").asText();

        mockMvc.perform(get("/media/{id}", mediaId))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG));
    }
}
