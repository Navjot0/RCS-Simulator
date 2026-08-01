package com.jio.rcs.operator.config.instance;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Loads external instance JSON configuration files that drive multi-instance
 * DLR callback routing for {@code /{instance}/wire/{provider}/...} requests
 * (see {@link InstanceRegistry}).
 *
 * <p>Directory-scans {@code operator.instances.directory} for {@code *.json}
 * files at startup, parses each generically with the shared Jackson {@link
 * ObjectMapper} into an {@link InstanceConfig}, validates it, and returns an
 * immutable {@code Map<String, InstanceConfig>} keyed by instance id.
 * Deliberately never hardcodes file names ({@code dev.json}/{@code
 * staging.json}/{@code cerf.json}/...) - adding a new instance is purely a
 * matter of dropping another JSON file into the directory and restarting;
 * this class has no notion of which instance names currently exist.
 *
 * <p>Runs once, at startup, from {@link InstanceRegistry}'s {@code
 * @PostConstruct} - there is no filesystem watcher, polling, or scheduled
 * reload (not required per the current spec); picking up a new/changed file
 * needs a restart.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InstanceConfigLoader {

    /** Same safe-identifier convention used elsewhere in this project for path-segment-derived values. */
    private static final Pattern INSTANCE_NAME_PATTERN = Pattern.compile("[a-zA-Z0-9_-]+");

    private final ObjectMapper objectMapper;

    /**
     * @param directory the configured {@code operator.instances.directory}. A missing/non-existent
     *                  directory is treated as "no instances configured yet" (logged at WARN, not a
     *                  startup failure) rather than preventing the simulator from starting - the
     *                  legacy, un-prefixed {@code /wire/{provider}/...} routes must keep working
     *                  regardless of whether multi-instance routing has been set up at all.
     * @throws IllegalStateException on anything that indicates genuinely broken configuration that
     *                                should stop the simulator from starting with a wrong/ambiguous
     *                                setup: malformed JSON, a missing/blank/invalid {@code instance}
     *                                field, an invalid {@code callbackUrl}, or two files claiming the
     *                                same instance id.
     */
    public Map<String, InstanceConfig> loadAll(Path directory) {
        if (!Files.isDirectory(directory)) {
            log.warn("Instance config directory '{}' does not exist or is not a directory - "
                    + "no multi-instance wire routing is available until it is created (with instance "
                    + "*.json files inside) and the simulator is restarted; legacy /wire/{{provider}}/... "
                    + "routes are unaffected", directory);
            return Map.of();
        }

        log.info("Loading instance configuration from {}", directory);

        List<Path> jsonFiles;
        try (Stream<Path> listing = Files.list(directory)) {
            jsonFiles = listing
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to list instance config directory '" + directory + "'", e);
        }

        Map<String, InstanceConfig> loaded = new LinkedHashMap<>();
        Map<String, Path> loadedFrom = new LinkedHashMap<>();

        for (Path file : jsonFiles) {
            InstanceConfig config = parse(file);
            validate(config, file);

            String instanceName = config.getInstance();
            Path existingSource = loadedFrom.get(instanceName);
            if (existingSource != null) {
                // Filesystem/listing order must never silently pick a winner between two
                // files that claim the same instance id - this is always a configuration
                // mistake and must be surfaced clearly at startup, not resolved implicitly.
                throw new IllegalStateException("Duplicate instance id '" + instanceName + "' found in both '"
                        + existingSource + "' and '" + file + "' - instance ids must be unique across " + directory);
            }

            loaded.put(instanceName, config);
            loadedFrom.put(instanceName, file);
            log.info("Loaded instance: {}", instanceName);
        }

        log.info("Loaded {} CPaaS instance configuration(s)", loaded.size());
        return Map.copyOf(loaded);
    }

    private InstanceConfig parse(Path file) {
        try {
            InstanceConfig config = objectMapper.readValue(file.toFile(), InstanceConfig.class);
            if (config == null) {
                throw new IllegalStateException("Instance config file '" + file + "' is empty");
            }
            return config;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to parse instance config file '" + file + "': " + e.getMessage(), e);
        }
    }

    private void validate(InstanceConfig config, Path file) {
        String instanceName = config.getInstance();
        if (instanceName == null || instanceName.isBlank()) {
            throw new IllegalStateException("Instance config file '" + file + "' is missing a required non-blank 'instance' field");
        }
        if (!INSTANCE_NAME_PATTERN.matcher(instanceName).matches()) {
            throw new IllegalStateException("Instance '" + instanceName + "' in '" + file
                    + "' has an invalid name - only letters, digits, '-' and '_' are allowed");
        }

        if (config.getProfiles() == null) {
            config.setProfiles(Map.of());
        }

        // A blank/missing callbackUrl for a given provider is a valid, expected
        // configuration shape (it means "this instance doesn't support that
        // provider yet" - rejected only at request time with
        // WireCallbackNotConfiguredException, see InstanceRegistry). What must be
        // rejected here at startup is a callbackUrl that *is* present but isn't
        // actually usable.
        for (Map.Entry<String, ProfileConfig> entry : config.getProfiles().entrySet()) {
            String provider = entry.getKey();
            ProfileConfig profile = entry.getValue();
            String callbackUrl = profile != null ? profile.getCallbackUrl() : null;
            if (callbackUrl == null || callbackUrl.isBlank()) {
                continue;
            }
            if (!isValidHttpUrl(callbackUrl)) {
                throw new IllegalStateException("Instance '" + instanceName + "' in '" + file + "' has an invalid "
                        + "callbackUrl for provider '" + provider + "': '" + callbackUrl + "' is not a valid http/https URL");
            }
        }
    }

    private boolean isValidHttpUrl(String candidate) {
        try {
            URI uri = new URI(candidate);
            String scheme = uri.getScheme();
            return uri.getHost() != null && ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme));
        } catch (URISyntaxException e) {
            return false;
        }
    }
}
