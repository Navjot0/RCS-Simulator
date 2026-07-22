package com.jio.rcs.operator.exception;

import org.springframework.http.HttpStatus;

public class MediaTooLargeException extends ProviderException {
    public MediaTooLargeException(String message) {
        super("PAYLOAD_TOO_LARGE", message, HttpStatus.PAYLOAD_TOO_LARGE);
    }
}
