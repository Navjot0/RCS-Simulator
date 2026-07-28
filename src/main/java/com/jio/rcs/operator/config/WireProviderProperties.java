package com.jio.rcs.operator.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Root binding for {@code operator.wire.profiles.<name>.*} - per real
 * provider wire-format profile (jio/dotgo/vi/airtel/...), where its DLR
 * webhooks should be POSTed. Deliberately separate from
 * {@link ProviderProperties#getCallbackUrl()}: that one field is a single
 * global fallback for the self-designed {@code /v1/messages} contract, but
 * each real-provider profile POSTs to a *different* CPaaS route
 * ({@code /rcs/webhook/jio}, {@code /rcs/webhook/dotgo}, etc.), so there is
 * no single sensible default to derive them from - every enabled profile
 * that's actually in use needs its own {@code callback-url} set explicitly.
 *
 * <p>Adding a new provider profile later needs no Java change here at all -
 * just a new {@code operator.wire.profiles.<name>.*} block in
 * application.properties, plus the matching {@code DlrFormatter} and wire
 * controller (see {@code com.jio.rcs.operator.wire}).
 */
@Data
@Component
@ConfigurationProperties(prefix = "operator.wire")
public class WireProviderProperties {

    private Map<String, Profile> profiles = new LinkedHashMap<>();

    /**
     * Single fixed OAuth2 access token shared by every wire profile's
     * simulated token endpoint (Jio/Dotgo/VI) - see
     * {@code operator.wire.static-access-token}. Deliberately one shared
     * value across all providers rather than per-profile, per explicit
     * request, so a known value can be hardcoded for manual testing
     * (curl/Postman) or reused by a future Bearer validation layer, instead
     * of a fresh token being generated on every token request.
     */
    private String staticAccessToken = "simulator-access-token";

    @Data
    public static class Profile {
        /** Set to false to make that provider's wire controller(s) return 404, e.g. to disable a profile you're not currently testing against. */
        private boolean enabled = true;

        /**
         * Where this profile's DLR webhooks are POSTed - normally the CPaaS
         * host's {@code /rcs/webhook/<profile>} route. Left blank means "no
         * webhook receiver configured for this profile" - CallbackEngine
         * then logs at DEBUG and skips sending, exactly like a message with
         * no callback URL under the self-designed contract.
         */
        private String callbackUrl;
    }

    /** True unless the profile has an explicit {@code enabled=false} entry - profiles are on by default so a brand-new one needs no config to start working. */
    public boolean isEnabled(String profile) {
        Profile p = profiles.get(profile);
        return p == null || p.isEnabled();
    }

    public String resolveCallbackUrl(String profile) {
        Profile p = profiles.get(profile);
        return p != null ? p.getCallbackUrl() : null;
    }
}
