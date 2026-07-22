package com.jio.rcs.operator.processor;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class ValidationResult {
    private final boolean valid;
    private final String errorCode;
    private final String errorDescription;

    public static ValidationResult ok() {
        return new ValidationResult(true, null, null);
    }

    public static ValidationResult reject(String code, String description) {
        return new ValidationResult(false, code, description);
    }
}
