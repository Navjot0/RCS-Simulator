package com.jio.rcs.operator.config.instance;

import com.jio.rcs.operator.config.ProviderProperties;
import com.jio.rcs.operator.exception.UnknownWireInstanceException;
import com.jio.rcs.operator.exception.WireCallbackNotConfiguredException;
import com.jio.rcs.operator.exception.WireInstanceDisabledException;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Map;

/**
 * Central, read-only-after-startup registry of every configured CPaaS
 * instance (dev/staging/cerf/uat/...), populated once at application
 * startup by {@link InstanceConfigLoader} scanning {@code
 * operator.instances.directory} for external {@code *.json} files - see
 * {@link ProviderProperties.Instances}.
 *
 * <p>This is the sole source of truth for multi-instance DLR callback
 * routing: it contains no hardcoded instance names anywhere, and {@link
 * com.jio.rcs.operator.wire.CallbackUrlResolver} does nothing but delegate
 * to {@link #resolveCallbackUrl(String, String)}. The backing map is
 * loaded exactly once and never mutated afterward (no filesystem watcher,
 * no polling, no runtime reload - adding/changing an instance requires a
 * restart), so concurrent lookups from different instances/providers can
 * never interfere with each other; there is no global mutable routing
 * state anywhere in this class.
 */
@Slf4j
@Component
public class InstanceRegistry {

    private final InstanceConfigLoader loader;
    private final ProviderProperties providerProperties;

    /**
     * Populated once by {@link #init()} (production/Spring path) or supplied
     * directly via {@link #InstanceRegistry(Map)} (test path) - never
     * reassigned after that point.
     */
    private volatile Map<String, InstanceConfig> instances;

    @Autowired
    public InstanceRegistry(InstanceConfigLoader loader, ProviderProperties providerProperties) {
        this.loader = loader;
        this.providerProperties = providerProperties;
    }

    /**
     * Test-only convenience constructor: builds an already-populated
     * registry directly from an in-memory map, bypassing directory
     * scanning entirely, so resolution logic can be exercised without
     * touching the filesystem. Never used by the Spring container (which
     * always goes through the other constructor + {@link #init()}).
     */
    public InstanceRegistry(Map<String, InstanceConfig> instances) {
        this.loader = null;
        this.providerProperties = null;
        this.instances = Map.copyOf(instances);
    }

    @PostConstruct
    void init() {
        Path directory = Path.of(providerProperties.getInstances().getDirectory());
        this.instances = loader.loadAll(directory);
    }

    /**
     * @param instance the {@code {instance}} path segment, e.g. "dev"/"staging"/"cerf"/"uat" - never null/blank when called (see WireIngestService, which only calls this for multi-instance requests).
     * @param provider the wire profile id, e.g. "jio"/"dotgo"/"vi"/"airtel".
     * @throws UnknownWireInstanceException       if no {@code <instance>.json} was loaded for {@code instance} at all.
     * @throws WireInstanceDisabledException      if the instance is known but its config has {@code "enabled": false}.
     * @throws WireCallbackNotConfiguredException if the instance is known and enabled, but has no usable (non-blank) callback URL for {@code provider}.
     */
    public String resolveCallbackUrl(String instance, String provider) {
        InstanceConfig config = instances.get(instance);
        if (config == null) {
            throw new UnknownWireInstanceException(instance);
        }
        if (!config.isEnabled()) {
            throw new WireInstanceDisabledException(instance);
        }

        ProfileConfig profile = config.getProfiles().get(provider);
        String callbackUrl = profile != null ? profile.getCallbackUrl() : null;
        if (callbackUrl == null || callbackUrl.isBlank()) {
            throw new WireCallbackNotConfiguredException(instance, provider);
        }
        return callbackUrl;
    }
}
