package com.jio.rcs.operator.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CallbackAttempt {
    private int attemptNumber;
    private Integer httpStatusCode;
    private String errorMessage;
    private boolean success;
    private Instant attemptedAt;
}
