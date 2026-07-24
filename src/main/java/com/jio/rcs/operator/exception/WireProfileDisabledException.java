package com.jio.rcs.operator.exception;

import org.springframework.http.HttpStatus;

/**
 * Thrown when a real-provider wire controller (see
 * {@code com.jio.rcs.operator.wire}) receives a request for a profile that
 * has been turned off via {@code operator.wire.profiles.<name>.enabled=false}.
 * Extends {@link ProviderException} so it's handled by the existing
 * {@code GlobalExceptionHandler} the same way every other simulated
 * provider-side error is, without needing a dedicated handler.
 */
public class WireProfileDisabledException extends ProviderException {
    public WireProfileDisabledException(String profile) {
        super("WIRE_PROFILE_DISABLED",
                "Wire profile '" + profile + "' is disabled (operator.wire.profiles." + profile + ".enabled=false)",
                HttpStatus.NOT_FOUND);
    }
}
