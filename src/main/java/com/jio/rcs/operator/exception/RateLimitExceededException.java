package com.jio.rcs.operator.exception;

import org.springframework.http.HttpStatus;

public class RateLimitExceededException extends ProviderException {
    public RateLimitExceededException(String message) {
        super("RATE_LIMIT", message, HttpStatus.TOO_MANY_REQUESTS);
    }
}
