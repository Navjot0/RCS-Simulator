package com.jio.rcs.operator.unit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jio.rcs.operator.callback.CallbackContentMapper;
import com.jio.rcs.operator.model.MessageContext;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the {@code {"ttl": ..., "content": <raw>}} envelope the DLR
 * webhook body's content comes from. Content is now fully dynamic and
 * opaque (see SendMessageRequest) - the mapper no longer reshapes typed
 * DTOs (rich card/carousel/media/suggestions) into a fixed GSMA wire
 * format, it just echoes back whatever JSON the caller originally sent,
 * unchanged, for any content shape.
 */
class CallbackContentMapperTest {

    private final CallbackContentMapper mapper = new CallbackContentMapper();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private JsonNode json(String raw) throws Exception {
        return objectMapper.readTree(raw);
    }

    @Test
    void wrapsRawContentInTtlEnvelopeUnchanged() throws Exception {
        MessageContext message = MessageContext.builder()
                .messageType("text")
                .content(json("{\"text\": \"hello world\"}"))
                .build();

        Map<String, Object> wire = mapper.toWireContent(message);

        assertThat(wire.get("ttl")).isEqualTo("1200s");
        assertThat(wire.get("content")).isEqualTo(json("{\"text\": \"hello world\"}"));
    }

    @Test
    void echoesAnArbitraryContentShapeVerbatim() throws Exception {
        // Proves the mapper doesn't know or care about any particular
        // content structure - a shape it's never seen before (a "poll")
        // round-trips exactly as sent.
        JsonNode pollContent = json("""
                {
                  "question": "Which flavor?",
                  "options": ["Vanilla", "Chocolate"],
                  "metadata": { "campaign": "summer-2026" }
                }
                """);

        MessageContext message = MessageContext.builder()
                .messageType("poll")
                .content(pollContent)
                .build();

        Map<String, Object> wire = mapper.toWireContent(message);

        assertThat(wire.get("content")).isEqualTo(pollContent);
    }

    @Test
    void nullContentRendersAsNullInsideTheEnvelope() {
        MessageContext message = MessageContext.builder()
                .messageType("text")
                .content(null)
                .build();

        Map<String, Object> wire = mapper.toWireContent(message);

        assertThat(wire).containsKey("content");
        assertThat(wire.get("content")).isNull();
        assertThat(wire.get("ttl")).isEqualTo("1200s");
    }
}
