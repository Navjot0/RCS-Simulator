package com.jio.rcs.operator.callback;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class CallbackDeliveryResult {
    private final boolean success;
    private final Integer httpStatusCode;
    private final String responseBody;
    private final String errorMessage;
}
