package com.jio.rcs.operator.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * End-to-end coverage for the {@code /{instance}/wire/{provider}/...}
 * multi-instance routing prefix added on top of the real-provider wire
 * endpoints (see com.jio.rcs.operator.wire). Instance definitions are
 * external JSON files (see {@code com.jio.rcs.operator.config.instance.InstanceConfigLoader}),
 * so this points {@code operator.instances.directory} at
 * {@code src/test/resources/instances}, which has dev/staging/cerf fully
 * configured for every provider (mirroring the values this simulator's
 * production {@code application.properties} used before instance config
 * moved out of properties and into external JSON) - this therefore runs in
 * its own separately-cached Spring context, not the default one shared by
 * plain {@code @SpringBootTest} classes with no property overrides.
 *
 * <p>These tests only assert on the synchronous HTTP response (same
 * convention as {@link WireFormatIntegrationTest}) - actual webhook
 * delivery to the configured dev/staging/cerf URLs isn't asserted here
 * (those hosts aren't reachable from the test run); the URL *resolution*
 * logic itself (which instance+provider maps to which URL, and the
 * negative/rejection cases) is exercised directly and cheaply by
 * {@code CallbackUrlResolverTest} (URL resolution logic) and {@code
 * InstanceConfigLoaderTest} (directory scanning/parsing/validation).
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "operator.instances.directory=src/test/resources/instances")
class MultiInstanceWireRoutingIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void devPrefixedViRouteAcceptsRequestJustLikeLegacyRoute() throws Exception {
        String body = """
                {"messageContact":{"userContact":"+919777000001"},"ttl":"10s","RCSMessage":{"textMessage":"hi"}}
                """;

        mockMvc.perform(post("/dev/wire/vi/rcs/bot/v1/{senderId}/messages/async", "sender-dev")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.RCSMessage.msgId").exists())
                .andExpect(jsonPath("$.RCSMessage.status").value("sent"));
    }

    @Test
    void stagingPrefixedViRouteAcceptsRequest() throws Exception {
        String body = """
                {"messageContact":{"userContact":"+919777000002"},"ttl":"10s","RCSMessage":{"textMessage":"hi"}}
                """;

        mockMvc.perform(post("/staging/wire/vi/rcs/bot/v1/{senderId}/messages/async", "sender-staging")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.RCSMessage.status").value("sent"));
    }

    @Test
    void cerfPrefixedViRouteAcceptsRequest() throws Exception {
        String body = """
                {"messageContact":{"userContact":"+919777000008"},"ttl":"10s","RCSMessage":{"textMessage":"hi"}}
                """;

        mockMvc.perform(post("/cerf/wire/vi/rcs/bot/v1/{senderId}/messages/async", "sender-cerf")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.RCSMessage.status").value("sent"));
    }

    @Test
    void devPrefixedJioRouteAcceptsRequestAndPreservesCpaasMessageId() throws Exception {
        String body = """
                {"content":{"plainText":"Hello from dev instance"}}
                """;

        mockMvc.perform(post("/dev/wire/jio/messaging/users/{to}/assistantMessages/async", "+919777000009")
                        .queryParam("messageId", "dev_jio_msg_001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("messaging/users/+919777000009/assistantMessages/dev_jio_msg_001"));

        mockMvc.perform(get("/v1/messages/{id}", "dev_jio_msg_001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.providerMessageId").value("dev_jio_msg_001"));
    }

    @Test
    void cerfPrefixedJioRouteAcceptsRequestAndPreservesCpaasMessageId() throws Exception {
        String body = """
                {"content":{"plainText":"Hello from cerf instance"}}
                """;

        mockMvc.perform(post("/cerf/wire/jio/messaging/users/{to}/assistantMessages/async", "+919777000003")
                        .queryParam("messageId", "cerf_jio_msg_001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("messaging/users/+919777000003/assistantMessages/cerf_jio_msg_001"));

        mockMvc.perform(get("/v1/messages/{id}", "cerf_jio_msg_001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.providerMessageId").value("cerf_jio_msg_001"));
    }

    @Test
    void differentInstancesHitDifferentAirtelRoutesIndependently() throws Exception {
        // Same provider (airtel), three different instance prefixes - each
        // must be accepted on its own, independent of the others (the
        // callback destination differs per instance, but that's invisible
        // at this synchronous-response layer; see CallbackUrlResolverTest
        // for direct proof the resolved URLs themselves never mix).
        String body = """
                {"customerId":"cust-1","subAccountId":"sub-1","agentId":"agent-multi","msisdn":"919666000001","templateId":"tmpl-1"}
                """;

        for (String instance : new String[] {"dev", "staging", "cerf"}) {
            mockMvc.perform(post("/" + instance + "/wire/airtel/conversation-message-acceptor/{version}/rcs/message/send", "v1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.status").value("INITIATED"));
        }
    }

    @Test
    void unknownInstanceIsRejectedImmediatelyWithNotFound() throws Exception {
        String body = """
                {"messageContact":{"userContact":"+919777000004"},"ttl":"10s","RCSMessage":{"textMessage":"hi"}}
                """;

        mockMvc.perform(post("/unknown/wire/vi/rcs/bot/v1/{senderId}/messages/async", "sender-unknown")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("UNKNOWN_WIRE_INSTANCE"));
    }

    @Test
    void legacyUnprefixedRouteStillWorksUnchangedAlongsideMultiInstanceRoutes() throws Exception {
        // Same request shape as WireFormatIntegrationTest.viBotAsyncAcceptsRealShapedRequest -
        // confirms the un-prefixed route isn't affected by instance routing
        // existing side-by-side with it in the same controller, and still
        // falls back to operator.wire.profiles.vi.callback-url (not any
        // operator.instances.*.profiles.vi.callback-url).
        String body = """
                {"messageContact":{"userContact":"+919777000006"},"ttl":"10s","RCSMessage":{"textMessage":"hi"}}
                """;

        mockMvc.perform(post("/wire/vi/rcs/bot/v1/{senderId}/messages/async", "sender-legacy")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.RCSMessage.msgId").exists())
                .andExpect(jsonPath("$.RCSMessage.status").value("sent"));
    }

    @Test
    void selfDesignedMessagesEndpointIsUnaffectedByMultiInstanceRouting() throws Exception {
        // POST /v1/messages must keep working exactly as before - multi-instance
        // routing only touches the provider wire endpoints under /wire/**.
        String body = """
                {"agent_id":"agent-multi-instance-test","to":["+919777000007"],"message_type":"text","content":{"plainText":"hi"}}
                """;

        mockMvc.perform(post("/v1/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.providerMessageId").exists());
    }
}
