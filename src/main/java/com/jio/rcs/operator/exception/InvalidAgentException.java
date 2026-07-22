package com.jio.rcs.operator.exception;

import org.springframework.http.HttpStatus;

public class InvalidAgentException extends ProviderException {
    public InvalidAgentException(String message) {
        super("INVALID_AGENT", message, HttpStatus.UNAUTHORIZED);
    }
}
