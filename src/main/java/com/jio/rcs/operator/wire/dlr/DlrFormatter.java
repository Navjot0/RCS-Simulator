package com.jio.rcs.operator.wire.dlr;

import com.jio.rcs.operator.model.MessageContext;
import com.jio.rcs.operator.statemachine.MessageState;

import java.util.Optional;

/**
 * Builds a DLR/status webhook payload in one real RCS provider's own wire
 * format, for messages ingested through that provider's wire-compatible
 * controller (see {@code com.jio.rcs.operator.wire}). Every implementation
 * was written directly against that provider's real webhook-consuming code
 * (e.g. {@code JioRcsWebhookProcessor.php}) rather than a guessed shape, so
 * a payload built here should be parseable by that real consumer without
 * any adapter-side special-casing.
 *
 * <p>One implementation per provider profile ("jio", "dotgo", "vi",
 * "airtel", ...), auto-discovered by {@link DlrFormatterRegistry} via
 * Spring component scanning and keyed by {@link #profileId()}. Adding a new
 * provider later is exactly one new {@code @Component} implementing this
 * interface plus one new wire-ingestion controller
 * (see {@code com.jio.rcs.operator.wire}) - no changes needed to
 * {@code CallbackEngine}, the registry, or any existing formatter.
 */
public interface DlrFormatter {

    /**
     * Must match both the wire-ingestion controller's providerProfile tag
     * (see {@code MessageContext.providerProfile}) and the real CPaaS
     * webhook route suffix this provider's DLRs are posted to (e.g. "jio"
     * -&gt; {@code /rcs/webhook/jio}).
     */
    String profileId();

    /**
     * Builds the webhook body for this state transition, or {@link Optional#empty()}
     * if this provider's real DLR contract has no distinct event for this
     * state (e.g. ACCEPTED/QUEUED are covered only by the synchronous
     * accept response for every provider implemented so far - there is no
     * "message was queued" webhook in any of their real contracts).
     */
    Optional<Object> build(MessageContext message, MessageState state);
}
