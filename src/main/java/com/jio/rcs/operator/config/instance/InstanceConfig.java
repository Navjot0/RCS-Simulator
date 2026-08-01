package com.jio.rcs.operator.config.instance;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * In-memory model of a single external instance JSON file (e.g.
 * {@code /config/instances/dev.json}) that drives multi-instance DLR
 * callback routing for {@code /{instance}/wire/{provider}/...} requests.
 *
 * <p>Example file contents:
 * <pre>{@code
 * {
 *   "instance": "dev",
 *   "enabled": true,
 *   "profiles": {
 *     "vi":     { "callbackUrl": "https://dev.example.com/rcs/vi/callback" },
 *     "jio":    { "callbackUrl": "https://dev.example.com/rcs/jio/callback" },
 *     "dotgo":  { "callbackUrl": "https://dev.example.com/rcs/dotgo/callback" },
 *     "airtel": { "callbackUrl": "https://dev.example.com/rcs/airtel/callback" }
 *   }
 * }
 * }</pre>
 *
 * <p>Deliberately a plain, generic {@code Map<String, ProfileConfig>} for
 * {@link #profiles} rather than one field per provider ({@code vi}/{@code
 * jio}/...) - adding a brand-new provider profile later must never require
 * changing this class, only the provider's own controller/{@code
 * DlrFormatter} plus JSON content (see {@link InstanceConfigLoader}/{@link
 * InstanceRegistry}). No routing or business logic lives here - this is a
 * pure data holder populated by Jackson; see {@link InstanceConfigLoader}
 * for parsing/validation and {@link InstanceRegistry} for lookup.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class InstanceConfig {

    /** The CPaaS instance id, e.g. "dev"/"staging"/"cerf"/"uat" - must be non-blank and match {@code [a-zA-Z0-9_-]+} (see InstanceConfigLoader). */
    private String instance;

    /** Whether this instance currently accepts wire traffic - false rejects every {@code /{instance}/wire/{provider}/...} request for it with WIRE_INSTANCE_DISABLED, without removing its configuration file. */
    private boolean enabled = true;

    /** Per-provider callback configuration, keyed by wire profile id ("vi"/"jio"/"dotgo"/"airtel"/...). Not every provider needs an entry - a provider missing here (or with a blank callbackUrl) simply isn't usable for this instance yet. */
    private Map<String, ProfileConfig> profiles = new LinkedHashMap<>();
}
