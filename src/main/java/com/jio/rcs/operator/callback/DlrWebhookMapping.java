package com.jio.rcs.operator.callback;

import com.jio.rcs.operator.statemachine.MessageState;

import java.util.Map;

/**
 * Maps each internal {@link MessageState} to the CPaaS webhook's
 * {@code event_type}, wire {@code status} string, and an internal "raw
 * provider event" name (used to build {@code additional_data}/
 * {@code delivery_status.webhook_status}). Confirmed mapping:
 *
 * <ul>
 *   <li>ACCEPTED fires no webhook at all - it's only the synchronous HTTP
 *   202 response from POST /v1/messages.</li>
 *   <li>QUEUED -&gt; message_dispatch, status "submitted".</li>
 *   <li>SUBMITTED -&gt; message_dispatch, status "sent".</li>
 *   <li>DELIVERED -&gt; message_delivery, status "delivered".</li>
 *   <li>DISPLAYED -&gt; message_delivery, status "read".</li>
 *   <li>FAILED / REJECTED / EXPIRED / UNKNOWN -&gt; message_delivery, status
 *   "failed" (these four differ only in delivery_info.failure_reason and
 *   the internal raw event name, not in the wire status string).</li>
 * </ul>
 */
public final class DlrWebhookMapping {

    public enum EventType {
        MESSAGE_DISPATCH("message_dispatch"),
        // Wire value is "delivery_report", not the more obvious
        // "message_delivery": confirmed by reading the CPaaS's own
        // JioRcsWebhookProcessor::mapNewFormatEventType(), whose lookup
        // table only recognizes event_type "message_dispatch",
        // "message_status", or "delivery_report" - "message_delivery" isn't
        // a key at all, so every DELIVERED/DISPLAYED/FAILED/REJECTED/
        // EXPIRED/UNKNOWN webhook this simulator sent under the old value
        // silently failed to match on the consuming side ("Unknown
        // event_type and status combination") and never updated the
        // message. The MESSAGE_DELIVERY enum constant itself (used for the
        // dispatch-vs-delivery branching in CallbackEngine) is unchanged -
        // only the string actually put on the wire.
        MESSAGE_DELIVERY("delivery_report"),
        NONE(null);

        private final String wireValue;

        EventType(String wireValue) {
            this.wireValue = wireValue;
        }

        public String wireValue() {
            return wireValue;
        }
    }

    public record Mapping(EventType eventType, String status, String rawEventName) {
    }

    // DELIVERED's rawEventName ("SEND_MESSAGE_SUCCESS") matches a real captured
    // provider payload and deliberately pairs with FAILED's "SEND_MESSAGE_FAILURE"
    // below - both describe the outcome of the same underlying send attempt.
    private static final Map<MessageState, Mapping> MAPPINGS = Map.ofEntries(
            Map.entry(MessageState.ACCEPTED, new Mapping(EventType.NONE, null, null)),
            Map.entry(MessageState.QUEUED, new Mapping(EventType.MESSAGE_DISPATCH, "submitted", "MESSAGE_SUBMITTED")),
            Map.entry(MessageState.SUBMITTED, new Mapping(EventType.MESSAGE_DISPATCH, "sent", "MESSAGE_SENT")),
            Map.entry(MessageState.DELIVERED, new Mapping(EventType.MESSAGE_DELIVERY, "delivered", "SEND_MESSAGE_SUCCESS")),
            Map.entry(MessageState.DISPLAYED, new Mapping(EventType.MESSAGE_DELIVERY, "read", "MESSAGE_READ")),
            Map.entry(MessageState.FAILED, new Mapping(EventType.MESSAGE_DELIVERY, "failed", "SEND_MESSAGE_FAILURE")),
            Map.entry(MessageState.REJECTED, new Mapping(EventType.MESSAGE_DELIVERY, "failed", "MESSAGE_REJECTED")),
            Map.entry(MessageState.EXPIRED, new Mapping(EventType.MESSAGE_DELIVERY, "failed", "MESSAGE_EXPIRED")),
            Map.entry(MessageState.UNKNOWN, new Mapping(EventType.MESSAGE_DELIVERY, "failed", "MESSAGE_UNKNOWN"))
    );

    private DlrWebhookMapping() {
    }

    public static Mapping forState(MessageState state) {
        return MAPPINGS.get(state);
    }
}
