package com.jio.rcs.operator.exception;

import org.springframework.http.HttpStatus;

public class InvalidStateTransitionException extends ProviderException {
    public InvalidStateTransitionException(String message) {
        super("INVALID_STATE_TRANSITION", message, HttpStatus.CONFLICT);
    }
}
