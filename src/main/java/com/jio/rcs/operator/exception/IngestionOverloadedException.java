package com.jio.rcs.operator.exception;

import org.springframework.http.HttpStatus;

/**
 * Thrown when {@code POST /v1/messages} (and the wire/bulk equivalents)
 * can't get the message onto the INCOMING queue within
 * {@code operator.queue.incoming-publish-timeout-millis} - i.e. the
 * pipeline is backed up badly enough that admitting this message would mean
 * blocking the client past that bound. 503, not 500/429: this isn't a
 * client mistake (400) or a clean rate-limit rejection (429, see
 * RateLimitExceededException) - it's the provider genuinely unable to keep
 * up with already-admitted work right now. Doesn't violate the zero-DLR-
 * loss guarantee (see InMemoryQueueService's Javadoc): the message was
 * never accepted into MessageStore/the pipeline in the first place, so
 * there's nothing to lose - this is a fast, explicit rejection at the
 * door instead of an unbounded hang that the client's own socket timeout
 * would eventually kill anyway with no useful response at all.
 */
public class IngestionOverloadedException extends ProviderException {
    public IngestionOverloadedException(String message) {
        super("PROVIDER_OVERLOADED", message, HttpStatus.SERVICE_UNAVAILABLE);
    }
}
