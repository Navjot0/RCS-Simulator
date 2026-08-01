package com.jio.rcs.operator.wire;

import com.jio.rcs.operator.config.instance.InstanceRegistry;
import com.jio.rcs.operator.exception.UnknownWireInstanceException;
import com.jio.rcs.operator.exception.WireCallbackNotConfiguredException;
import com.jio.rcs.operator.exception.WireInstanceDisabledException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Resolves the full DLR callback URL for a multi-instance wire request
 * ({@code /{instance}/wire/{provider}/...}) - purely a thin delegate to
 * {@link InstanceRegistry#resolveCallbackUrl(String, String)}, which is the
 * sole source of truth (backed by external instance JSON files scanned at
 * startup; see {@link com.jio.rcs.operator.config.instance.InstanceConfigLoader}).
 * No routing logic is hardcoded per instance name here or anywhere else, so
 * adding a new instance is a config-only change.
 *
 * <p>Stateless and holds no mutable fields of its own, so concurrent calls
 * for different instances/providers never interfere with each other - there
 * is nothing here that could leak one request's resolved URL into another's.
 *
 * <p>Called once, synchronously, at request ingestion (see
 * {@link WireIngestService}) - never at DLR-delivery time - so an unknown
 * instance, a disabled instance, or missing callback configuration is
 * rejected immediately with a clear error, before the message ever enters
 * the async pipeline, rather than being discovered only when
 * {@code CallbackEngine} tries to deliver a DLR minutes later.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CallbackUrlResolver {

    private final InstanceRegistry instanceRegistry;

    /**
     * @param instance the {@code {instance}} path segment, e.g. "dev"/"staging"/"cerf" - never null/blank when called (see WireIngestService, which only calls this for multi-instance requests).
     * @param provider the wire profile id, e.g. "jio"/"dotgo"/"vi"/"airtel".
     * @throws UnknownWireInstanceException       if {@code instance} has no loaded instance config at all.
     * @throws WireInstanceDisabledException      if the instance is known but disabled.
     * @throws WireCallbackNotConfiguredException if the instance is known and enabled, but has no usable (non-blank) callback-url for {@code provider}.
     */
    public String resolve(String instance, String provider) {
        String callbackUrl = instanceRegistry.resolveCallbackUrl(instance, provider);

        // INFO, not DEBUG - this fires once per multi-instance wire send, not
        // once per DLR/retry, so it's cheap, and it's exactly the line you
        // want when confirming a newly added instance actually routes where
        // you expect. Never logs credentials/tokens - callbackUrl is a plain
        // destination URL, nothing sensitive lives in it.
        log.info("Resolved wire callback: instance={} provider={} callbackUrl={}", instance, provider, callbackUrl);
        return callbackUrl;
    }
}
