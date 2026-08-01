package com.jio.rcs.operator.config.instance;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One real-provider wire profile's ("vi"/"jio"/"dotgo"/"airtel"/...) DLR
 * callback configuration within a single {@link InstanceConfig} - the JSON
 * shape bound is the {@code profiles.<provider>} object described in the
 * multi-instance DLR callback routing spec, e.g.:
 *
 * <pre>{@code
 * "vi": { "callbackUrl": "https://dev.example.com/rcs/vi/callback" }
 * }</pre>
 *
 * <p>Deliberately a single plain field, not richer per-provider structure -
 * every wire profile only ever needs one thing from instance config (where
 * to POST the DLR), never anything provider-specific here (that lives in
 * the provider's own {@code DlrFormatter}/wire controller instead). No
 * routing/business logic lives on this class - see {@link InstanceRegistry}
 * for lookup/resolution and {@link InstanceConfigLoader} for parsing/validation.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProfileConfig {

    /**
     * Full, complete callback URL - never built from a base-url + path
     * suffix, since environments don't share a common host or path shape.
     * May be {@code null}/blank, meaning "this instance does not support
     * this provider" - see {@link InstanceRegistry#resolveCallbackUrl}.
     */
    private String callbackUrl;
}
