package com.jio.rcs.operator.statemachine;

/**
 * Full lifecycle vocabulary for a simulated RCS message. The DEFAULT happy
 * path is ACCEPTED -> QUEUED -> SUBMITTED -> DELIVERED -> DISPLAYED.
 * FAILED / REJECTED / EXPIRED / UNKNOWN are terminal failure states that can
 * be reached from intermediate states, all governed by
 * operator.state-machine.transitions in application.properties.
 */
public enum MessageState {
    ACCEPTED,
    QUEUED,
    SUBMITTED,
    DELIVERED,
    DISPLAYED,
    FAILED,
    REJECTED,
    EXPIRED,
    UNKNOWN;

    public boolean isTerminal() {
        return this == DISPLAYED || this == FAILED || this == REJECTED
                || this == EXPIRED || this == UNKNOWN;
    }
}
