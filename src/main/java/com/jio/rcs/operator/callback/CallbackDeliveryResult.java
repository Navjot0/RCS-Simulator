package com.jio.rcs.operator.callback;

import lombok.Getter;

@Getter
public class CallbackDeliveryResult {
    private final boolean success;
    private final Integer httpStatusCode;
    private final String responseBody;
    private final String errorMessage;

    /**
     * False when this specific failure can never succeed on retry - e.g. a
     * 404 (the callback URL doesn't exist at that destination) or a DNS
     * resolution failure (the host doesn't exist/can't be resolved at all).
     * True for every other failure (timeouts, connection refused, 5xx,
     * etc.) and irrelevant when {@code success} is true. See
     * CallbackEngine.attempt, which skips straight to DEAD_LETTERED without
     * scheduling any retry when this is false - retrying a URL that
     * fundamentally doesn't exist just burns attempts/backoff time for a
     * result that will never change.
     */
    private final boolean retryable;

    /**
     * Kept for backward compatibility with existing callers/tests that
     * don't need to express "not retryable" - defaults retryable to true,
     * matching every failure mode this simulator used to treat uniformly.
     */
    public CallbackDeliveryResult(boolean success, Integer httpStatusCode, String responseBody, String errorMessage) {
        this(success, httpStatusCode, responseBody, errorMessage, true);
    }

    public CallbackDeliveryResult(boolean success, Integer httpStatusCode, String responseBody, String errorMessage, boolean retryable) {
        this.success = success;
        this.httpStatusCode = httpStatusCode;
        this.responseBody = responseBody;
        this.errorMessage = errorMessage;
        this.retryable = retryable;
    }
}
