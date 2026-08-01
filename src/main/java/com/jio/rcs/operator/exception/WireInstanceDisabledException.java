package com.jio.rcs.operator.exception;

import org.springframework.http.HttpStatus;

/**
 * Thrown when a multi-instance wire request ({@code /{instance}/wire/{provider}/...})
 * names a known, configured instance whose external JSON config file has
 * {@code "enabled": false} - e.g. {@code /dev/wire/vi/...} while
 * {@code dev.json} is temporarily disabled. Rejected synchronously at
 * ingestion, before the message ever enters the async pipeline (see
 * {@code com.jio.rcs.operator.config.instance.InstanceRegistry}). Extends
 * {@link ProviderException} so it's handled by the existing {@code
 * GlobalExceptionHandler} the same way every other simulated provider-side
 * error is, without needing a dedicated handler.
 *
 * <p>Distinct from {@link WireProfileDisabledException}: that one disables
 * an entire real-provider wire profile (vi/jio/dotgo/airtel) across every
 * instance and the legacy un-prefixed route, whereas this one disables a
 * single CPaaS instance while leaving every other instance (and the legacy
 * route) unaffected.
 */
public class WireInstanceDisabledException extends ProviderException {
    public WireInstanceDisabledException(String instance) {
        super("WIRE_INSTANCE_DISABLED",
                "Instance '" + instance + "' is disabled",
                HttpStatus.NOT_FOUND);
    }
}
