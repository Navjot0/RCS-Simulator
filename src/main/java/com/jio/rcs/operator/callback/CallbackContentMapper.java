package com.jio.rcs.operator.callback;

import com.jio.rcs.operator.model.MessageContext;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Wraps a message's dynamic, caller-supplied {@code content} JSON (see
 * {@link com.jio.rcs.operator.dto.request.SendMessageRequest}) in the
 * {@code {"ttl": "...", "content": {...}}} envelope the CPaaS platform's own
 * DLR/dispatch webhooks use.
 *
 * <p>Earlier versions of this simulator reshaped the request's typed
 * content DTOs (rich card/carousel/media/suggestions) into a fixed GSMA
 * wire format here. Now that {@code content} is an opaque, fully dynamic
 * JSON value the simulator never inspects, there is nothing left to
 * reshape - whatever JSON the caller sent as {@code content} is echoed back
 * unchanged inside this envelope. That also means a webhook consumer sees
 * exactly the same content shape it sent, for any {@code message_type},
 * including ones this simulator has never seen before.
 */
@Component
public class CallbackContentMapper {

    private static final String DEFAULT_TTL = "1200s";

    /** Builds the {@code {"ttl": ..., "content": <raw>}} envelope for a message. */
    public Map<String, Object> toWireContent(MessageContext message) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("ttl", DEFAULT_TTL);
        envelope.put("content", message.getContent());
        return envelope;
    }
}
