package com.jio.rcs.operator.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Every instance x provider combination is fully configured in
 * {@code src/test/resources/instances} (used by {@link
 * MultiInstanceWireRoutingIntegrationTest}), so the "known instance, but
 * that provider's callback isn't configured" rejection path ({@link
 * com.jio.rcs.operator.exception.WireCallbackNotConfiguredException}) can't
 * be reached against that directory. This test instead points {@code
 * operator.instances.directory} at {@code src/test/resources/instances-missing-dotgo},
 * a separate directory whose {@code dev.json} has a blank {@code
 * dotgo.callbackUrl} (staging/cerf are unaffected, fully configured, same
 * as the other directory) - in its own, separately-cached Spring context, so
 * it doesn't affect the fully-configured context {@link
 * MultiInstanceWireRoutingIntegrationTest} runs against - to prove the
 * rejection still fires correctly, and only for that one instance+provider
 * pair, even when every other combination is fully configured.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "operator.instances.directory=src/test/resources/instances-missing-dotgo")
class MultiInstanceMissingCallbackConfigIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void knownInstanceWithBlankCallbackForOneProviderIsRejected() throws Exception {
        String body = """
                {"messageContact":{"userContact":"+919777000005"},"ttl":"10s","RCSMessage":{"textMessage":"hi"}}
                """;

        // dev/dotgo's callback-url is blanked out for this test only - must
        // fail clearly, not silently fall back to operator.wire.profiles
        // .dotgo.callback-url or another instance's dotgo URL.
        mockMvc.perform(post("/dev/wire/dotgo/rcs/bot/v1/{senderId}/messages/async", "sender-dev")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("WIRE_CALLBACK_NOT_CONFIGURED"));
    }

    @Test
    void sameInstanceDifferentProviderStillResolvesNormally() throws Exception {
        // dev/vi is untouched by the property override above - proves the
        // blank dev/dotgo value doesn't leak into or disable any other
        // provider under the same instance.
        String body = """
                {"messageContact":{"userContact":"+919777000010"},"ttl":"10s","RCSMessage":{"textMessage":"hi"}}
                """;

        mockMvc.perform(post("/dev/wire/vi/rcs/bot/v1/{senderId}/messages/async", "sender-dev")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.RCSMessage.status").value("sent"));
    }
}
