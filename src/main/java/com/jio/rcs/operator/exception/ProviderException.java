package com.jio.rcs.operator.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * Base type for simulated provider-side errors. Carries the provider error
 * code/description pair that gets surfaced to callers and recorded on the
 * message + DLR, mirroring how a real operator reports failures.
 */
@Getter
public class ProviderException extends RuntimeException {

    private final String errorCode;
    private final HttpStatus httpStatus;

    public ProviderException(String errorCode, String message, HttpStatus httpStatus) {
        super(message);
        this.errorCode = errorCode;
        this.httpStatus = httpStatus;
    }
}
