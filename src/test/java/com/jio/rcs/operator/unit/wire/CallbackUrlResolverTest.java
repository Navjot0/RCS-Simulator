package com.jio.rcs.operator.unit.wire;

import com.jio.rcs.operator.config.instance.InstanceConfig;
import com.jio.rcs.operator.config.instance.InstanceRegistry;
import com.jio.rcs.operator.config.instance.ProfileConfig;
import com.jio.rcs.operator.exception.UnknownWireInstanceException;
import com.jio.rcs.operator.exception.WireCallbackNotConfiguredException;
import com.jio.rcs.operator.exception.WireInstanceDisabledException;
import com.jio.rcs.operator.wire.CallbackUrlResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Covers the multi-instance DLR callback routing spec's test scenarios that
 * are cheapest to verify directly against {@link CallbackUrlResolver} in
 * isolation (no Spring context, no HTTP layer, no filesystem/JSON parsing):
 * per-instance / per-provider resolution correctness, independence between
 * instances and between providers, unknown-instance / disabled-instance /
 * missing-config / blank-callback-url rejection, and concurrent resolution
 * across instances never mixing results (the resolver is stateless, so
 * this is really a sanity check that it stays that way).
 *
 * <p>{@link CallbackUrlResolver} itself does nothing but delegate to
 * {@link InstanceRegistry#resolveCallbackUrl(String, String)}, so this test
 * builds an {@link InstanceRegistry} directly from an in-memory {@code
 * Map<String, InstanceConfig>} via its test-only constructor - the exact
 * same shape {@link com.jio.rcs.operator.config.instance.InstanceConfigLoader}
 * would have produced from real {@code *.json} files, without touching the
 * filesystem. See {@code InstanceConfigLoaderTest} for coverage of the
 * directory-scanning/parsing/validation behaviour itself.
 *
 * <p>dev/staging/cerf below use the same URLs actually shipped in {@code
 * src/test/resources/instances/*.json} (webhook.gtsstaging.com,
 * webhook.cpaas.globeteleservices.com, webhook.cerfsolutions.com) rather
 * than placeholder domains. A fourth, purely hypothetical {@code uat}
 * instance (not present in that directory) is used only for the
 * missing-config / blank-callback-url negative cases, standing in for "a
 * new instance that's been partially set up", and a fifth, {@code
 * disabled}, instance covers the disabled-instance rejection path.
 */
class CallbackUrlResolverTest {

    private CallbackUrlResolver resolver;

    @BeforeEach
    void setUp() {
        Map<String, InstanceConfig> instances = new LinkedHashMap<>();

        instances.put("dev", instanceOf("dev", true, Map.of(
                "jio", "https://webhook.gtsstaging.com/api/rcs/webhook/jio",
                "dotgo", "https://webhook.gtsstaging.com/api/rcs/webhook/dotgo",
                "vi", "https://webhook.gtsstaging.com/api/rcs/webhook/vi",
                "airtel", "https://webhook.gtsstaging.com/api/rcs/webhook/airtel"
        )));
        instances.put("staging", instanceOf("staging", true, Map.of(
                "jio", "https://webhook.cpaas.globeteleservices.com/api/rcs/webhook/jio",
                "dotgo", "https://webhook.cpaas.globeteleservices.com/api/rcs/webhook/dotgo",
                "vi", "https://webhook.cpaas.globeteleservices.com/api/rcs/webhook/vi",
                "airtel", "https://webhook.cpaas.globeteleservices.com/api/rcs/webhook/airtel"
        )));
        instances.put("cerf", instanceOf("cerf", true, Map.of(
                "jio", "https://webhook.cerfsolutions.com/api/rcs/webhook/jio",
                "dotgo", "https://webhook.cerfsolutions.com/api/rcs/webhook/dotgo",
                "vi", "https://webhook.cerfsolutions.com/api/rcs/webhook/vi",
                "airtel", "https://webhook.cerfsolutions.com/api/rcs/webhook/airtel"
        )));
        // "uat" is not a real configured instance - it stands in for "a new
        // instance that's only been partially set up": vi is configured,
        // dotgo's key is missing entirely, and airtel is present but blank.
        // Both gaps must be rejected the same way
        // (WireCallbackNotConfiguredException), not fall back to any other
        // instance/provider's URL.
        Map<String, String> uatProfiles = new LinkedHashMap<>();
        uatProfiles.put("vi", "https://uat.example-cpaas.internal/api/rcs/webhook/vi");
        uatProfiles.put("airtel", "   ");
        instances.put("uat", instanceOf("uat", true, uatProfiles));

        // A known, fully-configured instance that's simply been switched off -
        // must be rejected outright, distinct from "unknown" and from
        // "missing/blank callback".
        instances.put("disabled", instanceOf("disabled", false, Map.of(
                "vi", "https://disabled.example.com/vi/webhook"
        )));

        resolver = new CallbackUrlResolver(new InstanceRegistry(instances));
    }

    private InstanceConfig instanceOf(String name, boolean enabled, Map<String, String> profileCallbackUrls) {
        Map<String, ProfileConfig> profiles = new LinkedHashMap<>();
        profileCallbackUrls.forEach((provider, url) -> profiles.put(provider, new ProfileConfig(url)));
        return new InstanceConfig(name, enabled, profiles);
    }

    // 1-3: dev/staging/cerf VI resolution
    @Test
    void devViResolvesDevViCallback() {
        assertThat(resolver.resolve("dev", "vi")).isEqualTo("https://webhook.gtsstaging.com/api/rcs/webhook/vi");
    }

    @Test
    void stagingViResolvesStagingViCallback() {
        assertThat(resolver.resolve("staging", "vi")).isEqualTo("https://webhook.cpaas.globeteleservices.com/api/rcs/webhook/vi");
    }

    @Test
    void cerfViResolvesCerfViCallback() {
        assertThat(resolver.resolve("cerf", "vi")).isEqualTo("https://webhook.cerfsolutions.com/api/rcs/webhook/vi");
    }

    // 4-5: dev/cerf Jio resolution
    @Test
    void devJioResolvesDevJioCallback() {
        assertThat(resolver.resolve("dev", "jio")).isEqualTo("https://webhook.gtsstaging.com/api/rcs/webhook/jio");
    }

    @Test
    void cerfJioResolvesCerfJioCallback() {
        assertThat(resolver.resolve("cerf", "jio")).isEqualTo("https://webhook.cerfsolutions.com/api/rcs/webhook/jio");
    }

    // 6: different providers under the same instance resolve independently
    @Test
    void differentProvidersUnderSameInstanceResolveIndependently() {
        assertThat(resolver.resolve("dev", "jio")).isEqualTo("https://webhook.gtsstaging.com/api/rcs/webhook/jio");
        assertThat(resolver.resolve("dev", "dotgo")).isEqualTo("https://webhook.gtsstaging.com/api/rcs/webhook/dotgo");
        assertThat(resolver.resolve("dev", "vi")).isEqualTo("https://webhook.gtsstaging.com/api/rcs/webhook/vi");
        assertThat(resolver.resolve("dev", "airtel")).isEqualTo("https://webhook.gtsstaging.com/api/rcs/webhook/airtel");
    }

    // 7: same provider under different instances resolves independently
    @Test
    void sameProviderUnderDifferentInstancesResolvesIndependently() {
        assertThat(resolver.resolve("dev", "vi")).isEqualTo("https://webhook.gtsstaging.com/api/rcs/webhook/vi");
        assertThat(resolver.resolve("staging", "vi")).isEqualTo("https://webhook.cpaas.globeteleservices.com/api/rcs/webhook/vi");
        assertThat(resolver.resolve("cerf", "vi")).isEqualTo("https://webhook.cerfsolutions.com/api/rcs/webhook/vi");
    }

    // 8: concurrent dev/staging/cerf requests never mix callback destinations
    @Test
    void concurrentResolutionsAcrossInstancesNeverMix() throws Exception {
        Map<String, String> expected = Map.of(
                "dev", "https://webhook.gtsstaging.com/api/rcs/webhook/vi",
                "staging", "https://webhook.cpaas.globeteleservices.com/api/rcs/webhook/vi",
                "cerf", "https://webhook.cerfsolutions.com/api/rcs/webhook/vi"
        );
        List<String> instanceCycle = List.of("dev", "staging", "cerf");
        int iterationsPerInstance = 200;

        List<Callable<Boolean>> tasks = IntStream.range(0, iterationsPerInstance * instanceCycle.size())
                .mapToObj(i -> instanceCycle.get(i % instanceCycle.size()))
                .map(instance -> (Callable<Boolean>) () -> {
                    String resolved = resolver.resolve(instance, "vi");
                    return expected.get(instance).equals(resolved);
                })
                .collect(Collectors.toList());

        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            List<Future<Boolean>> results = pool.invokeAll(tasks);
            for (Future<Boolean> result : results) {
                assertThat(result.get()).isTrue();
            }
        } finally {
            pool.shutdown();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    // 9: unknown instance is rejected
    @Test
    void unknownInstanceIsRejected() {
        assertThatThrownBy(() -> resolver.resolve("unknown", "vi"))
                .isInstanceOf(UnknownWireInstanceException.class);
    }

    // 10: missing provider configuration is rejected (known instance, provider key absent)
    @Test
    void missingProviderConfigurationIsRejected() {
        assertThatThrownBy(() -> resolver.resolve("uat", "dotgo"))
                .isInstanceOf(WireCallbackNotConfiguredException.class);
    }

    // 11: blank callback URL is handled correctly (known instance+provider, blank value)
    @Test
    void blankCallbackUrlIsRejected() {
        assertThatThrownBy(() -> resolver.resolve("uat", "airtel"))
                .isInstanceOf(WireCallbackNotConfiguredException.class);
    }

    // 12: disabled instance is rejected, distinct from unknown/missing-config
    @Test
    void disabledInstanceIsRejected() {
        assertThatThrownBy(() -> resolver.resolve("disabled", "vi"))
                .isInstanceOf(WireInstanceDisabledException.class);
    }

    @Test
    void doesNotFallBackToAnotherInstanceOrProviderOnFailure() {
        // No failure mode should ever silently resolve to some other
        // instance's or provider's URL - each must throw, not substitute.
        assertThatThrownBy(() -> resolver.resolve("unknown", "vi"))
                .isInstanceOf(UnknownWireInstanceException.class)
                .hasMessageContaining("unknown");
        assertThatThrownBy(() -> resolver.resolve("uat", "dotgo"))
                .isInstanceOf(WireCallbackNotConfiguredException.class)
                .hasMessageContaining("uat")
                .hasMessageContaining("dotgo");
        assertThatThrownBy(() -> resolver.resolve("disabled", "vi"))
                .isInstanceOf(WireInstanceDisabledException.class)
                .hasMessageContaining("disabled");
    }
}
