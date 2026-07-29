package com.jio.rcs.operator.exception;

import org.springframework.http.HttpStatus;

/**
 * Thrown when a multi-instance wire request ({@code /{instance}/wire/{provider}/...})
 * names a real, configured instance, but that instance has no usable
 * callback URL for the requested provider - either the
 * {@code operator.instances.<instance>.profiles.<provider>} entry is
 * missing entirely, or its {@code callback-url} is blank (e.g. an
 * {@code ${ENV_VAR:}} placeholder that was never actually set). Rejected
 * synchronously at ingestion rather than silently falling back to another
 * instance/provider's callback or queuing the message and discovering the
 * gap later. Extends {@link ProviderException} so it's handled by the
 * existing {@code GlobalExceptionHandler} without a dedicated handler.
 */
public class WireCallbackNotConfiguredException extends ProviderException {
    public WireCallbackNotConfiguredException(String instance, String provider) {
        super("WIRE_CALLBACK_NOT_CONFIGURED",
                "No callback URL configured for instance='" + instance + "', provider='" + provider
                        + "' (operator.instances." + instance + ".profiles." + provider + ".callback-url is missing or blank)",
                HttpStatus.NOT_FOUND);
    }
}
