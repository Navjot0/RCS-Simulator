package com.jio.rcs.operator.processor;

import com.jio.rcs.operator.model.MessageContext;

/**
 * Strategy Pattern hook for deciding a message's terminal DLR outcome.
 * Swap the bean to change simulation behaviour (e.g. an "always succeed"
 * strategy for smoke-testing) without touching the DLR engine or scheduler.
 */
public interface DlrOutcomeStrategy {
    DlrOutcome decideOutcome(MessageContext message);
}
