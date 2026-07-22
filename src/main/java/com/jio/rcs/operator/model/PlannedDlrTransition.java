package com.jio.rcs.operator.model;

import com.jio.rcs.operator.statemachine.MessageState;

import java.time.Instant;

/**
 * One step of a message's precomputed DLR lifecycle plan (see
 * {@code MessageContext.pendingDlrTransitions} and
 * {@code com.jio.rcs.operator.processor.DlrEngine}).
 *
 * <p>{@code fireAt} is the "ideal" time this transition should happen,
 * computed once up front from the message's acceptance time and the active
 * provider profile's configured per-state delay. It is only a target,
 * though: the engine schedules the transition AFTER it, not instead of it -
 * see DlrEngine's Javadoc for why each step is only enqueued once the
 * previous one has actually been confirmed applied, rather than all steps
 * being scheduled independently up front against the wall clock.
 */
public record PlannedDlrTransition(MessageState state, String errorCode, String errorDescription, Instant fireAt) {
}
