package com.jio.rcs.operator.exception;

import org.springframework.http.HttpStatus;

/**
 * Thrown when a multi-instance wire request ({@code /{instance}/wire/{provider}/...})
 * names an instance with no corresponding {@code operator.instances.<name>.*}
 * configuration entry at all - e.g. {@code /unknown/wire/vi/...}. Rejected
 * synchronously at ingestion, before the message ever enters the async
 * pipeline (see {@code com.jio.rcs.operator.wire.CallbackUrlResolver}).
 * Extends {@link ProviderException} so it's handled by the existing
 * {@code GlobalExceptionHandler} the same way every other simulated
 * provider-side error is, without needing a dedicated handler.
 */
public class UnknownWireInstanceException extends ProviderException {
    public UnknownWireInstanceException(String instance) {
        super("UNKNOWN_WIRE_INSTANCE",
                "Unknown wire instance '" + instance + "' - no operator.instances." + instance + ".* configuration found",
                HttpStatus.NOT_FOUND);
    }
}
