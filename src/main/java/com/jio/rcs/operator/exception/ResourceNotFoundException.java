package com.jio.rcs.operator.exception;

import org.springframework.http.HttpStatus;

public class ResourceNotFoundException extends ProviderException {
    public ResourceNotFoundException(String message) {
        super("NOT_FOUND", message, HttpStatus.NOT_FOUND);
    }
}
