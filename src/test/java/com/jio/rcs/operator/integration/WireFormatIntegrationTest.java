package com.jio.rcs.operator.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * End-to-end sanity check for the real-provider wire-format endpoints (see
 * com.jio.rcs.operator.wire) - each one accepts a request shaped exactly
 * like the real provider's own contract (not this simulator's self-designed
 * POST /v1/messages), and the synchronous response echoes back the id a
 * CPaaS provider adapter expects to see so it can correlate later DLRs.
 * Webhook delivery itself is covered separately by the per-profile
 * DlrFormatter unit tests - operator.wire.profiles.*.callback-url is blank
 * in application.properties (there's no single sensible default host for
 * it, see WireProviderProperties), so CallbackEngine skips sending here.
 */
@SpringBootTest
@AutoConfigureMockMvc
class WireFormatIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void jioAssistantMessagesAsyncAcceptsRealShapedRequest() throws Exception {
        String body = """
                {"content":{"plainText":"Hello from Jio wire format test"}}
                """;

        mockMvc.perform(post("/wire/jio/messaging/users/{to}/assistantMessages/async", "+919999999999")
                        .queryParam("messageId", "jio_test_msg_001")
                        .queryParam("assistantId", "assistant-42")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("messaging/users/+919999999999/assistantMessages/jio_test_msg_001"));

        // The CPaaS-generated messageId query param must become the
        // providerMessageId, not a simulator-minted one, or later DLRs
        // could never be correlated back by the real Jio webhook processor.
        mockMvc.perform(get("/v1/messages/{id}", "jio_test_msg_001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.providerMessageId").value("jio_test_msg_001"));
    }

    @Test
    void jioOAuthTokenEndpointReturnsSimulatedBearerToken() throws Exception {
        mockMvc.perform(get("/wire/jio/v1/oauth/token")
                        .queryParam("grant_type", "client_credentials")
                        .queryParam("client_id", "any-client")
                        .queryParam("client_secret", "any-secret")
                        .queryParam("scope", "read"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.access_token").exists())
                .andExpect(jsonPath("$.token_type").value("Bearer"));
    }

    @Test
    void dotgoLegacyBotAsyncAcceptsRealShapedRequestAndReturnsMintedMsgId() throws Exception {
        String body = """
                {"messageContact":{"userContact":"+919888888888"},"ttl":"10s","RCSMessage":{"textMessage":"hi"}}
                """;

        mockMvc.perform(post("/wire/dotgo/rcs/bot/v1/{senderId}/messages/async", "sender-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.RCSMessage.msgId").exists())
                .andExpect(jsonPath("$.RCSMessage.status").value("sent"));
    }

    @Test
    void dotgoAgentMessagesAsyncAcceptsRealShapedRequest() throws Exception {
        String body = """
                {"contentMessage":{"text":"hi"},"ttl":"10s"}
                """;

        mockMvc.perform(post("/wire/dotgo/rcs/v1/phones/{phone}/agentMessages/async", "+919888888888")
                        .queryParam("botId", "bot-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messageId").exists());
    }

    @Test
    void viBotAsyncAcceptsRealShapedRequest() throws Exception {
        String body = """
                {"messageContact":{"userContact":"+919777777777"},"ttl":"10s","RCSMessage":{"textMessage":"hi"}}
                """;

        mockMvc.perform(post("/wire/vi/rcs/bot/v1/{senderId}/messages/async", "sender-vi")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.RCSMessage.msgId").exists())
                .andExpect(jsonPath("$.RCSMessage.status").value("sent"));
    }

    @Test
    void airtelMessageSendAcceptsRealShapedRequest() throws Exception {
        String body = """
                {"customerId":"cust-1","subAccountId":"sub-1","agentId":"agent-7","msisdn":"919666666666","templateId":"tmpl-1"}
                """;

        mockMvc.perform(post("/wire/airtel/conversation-message-acceptor/{version}/rcs/message/send", "v1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.status").value("INITIATED"))
                .andExpect(jsonPath("$.messageRequestId").exists());
    }
}
