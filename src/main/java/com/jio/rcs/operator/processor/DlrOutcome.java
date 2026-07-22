package com.jio.rcs.operator.processor;

import com.jio.rcs.operator.statemachine.MessageState;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class DlrOutcome {
    private final MessageState terminalState;
    private final boolean displayedAfterDelivered;
    private final String errorCode;
    private final String errorDescription;
}
