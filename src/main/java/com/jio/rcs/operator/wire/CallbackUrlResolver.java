package com.jio.rcs.operator.wire;

import com.jio.rcs.operator.config.ProviderProperties;
import com.jio.rcs.operator.exception.UnknownWireInstanceException;
import com.jio.rcs.operator.exception.WireCallbackNotConfiguredException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Resolves the full DLR callback URL for a multi-instance wire request
 * ({@code /{instance}/wire/{provider}/...}) purely from
 * {@code operator.instances.<instance>.profiles.<provider>.callback-url}
 * (see {@link ProviderProperties#getInstances()}) - no routing logic is
 * hardcoded per instance name here or anywhere else; the configuration map
 * is the sole source of truth, so adding a new instance is a config-only
 * change.
 *
 * <p>Stateless and holds no mutable fields of its own (just an injected,
 * effectively-immutable-after-startup {@link ProviderProperties}), so
 * concurrent calls for different instances/providers never interfere with
 * each other - there is nothing here that could leak one request's resolved
 * URL into another's.
 *
 * <p>Called once, synchronously, at request ingestion (see
 * {@link WireIngestService}) - never at DLR-delivery time - so an unknown
 * instance or missing callback configuration is rejected immediately with a
 * clear error, before the message ever enters the async pipeline, rather
 * than being discovered only when {@code CallbackEngine} tries to deliver a
 * DLR minutes later.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CallbackUrlResolver {

    private final ProviderProperties providerProperties;

    /**
     * @param instance the {@code {instance}} path segment, e.g. "dev"/"staging"/"cerf" - never null/blank when called (see WireIngestService, which only calls this for multi-instance requests).
     * @param provider the wire profile id, e.g. "jio"/"dotgo"/"vi"/"airtel".
     * @throws UnknownWireInstanceException      if {@code instance} has no {@code operator.instances.<instance>.*} entry at all.
     * @throws WireCallbackNotConfiguredException if the instance is known but has no usable (non-blank) callback-url for {@code provider}.
     */
    public String resolve(String instance, String provider) {
        ProviderProperties.Instance instanceConfig = providerProperties.getInstances().get(instance);
        if (instanceConfig == null) {
            throw new UnknownWireInstanceException(instance);
        }

        ProviderProperties.InstanceProfile profileConfig = instanceConfig.getProfiles().get(provider);
        String callbackUrl = profileConfig != null ? profileConfig.getCallbackUrl() : null;
        if (callbackUrl == null || callbackUrl.isBlank()) {
            throw new WireCallbackNotConfiguredException(instance, provider);
        }

        // DEBUG, not INFO (reversed from an earlier deliberate choice - see
        // git history: this fires once per multi-instance wire send, not
        // once per DLR/retry, so it looked cheap in isolation - but "once
        // per send" is still once per request at TPS scale, up to 20,000+/sec
        // on the request-handling thread itself. Same virtual-thread-pinning
        // risk as CallbackEngine's DLR-delivered line - see that class for
        // the full explanation. Set logging.level.com.jio.rcs.operator=DEBUG
        // when confirming a newly added instance actually routes where you
        // expect. Never logs credentials/tokens - callbackUrl is a plain
        // destination URL, nothing sensitive lives in it.
        log.debug("Resolved wire callback: instance={} provider={} callbackUrl={}", instance, provider, callbackUrl);
        return callbackUrl;
    }
}
