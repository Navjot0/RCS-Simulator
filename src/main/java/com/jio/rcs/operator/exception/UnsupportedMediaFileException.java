package com.jio.rcs.operator.exception;

import org.springframework.http.HttpStatus;

public class UnsupportedMediaFileException extends ProviderException {
    public UnsupportedMediaFileException(String message) {
        super("UNSUPPORTED_MEDIA_TYPE", message, HttpStatus.UNSUPPORTED_MEDIA_TYPE);
    }
}
