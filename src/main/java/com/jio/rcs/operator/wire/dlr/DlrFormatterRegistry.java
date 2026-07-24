package com.jio.rcs.operator.wire.dlr;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Looks up the {@link DlrFormatter} for a given provider profile.
 * Spring injects every {@code @Component}-annotated {@link DlrFormatter}
 * bean found on the classpath here automatically - this class never needs
 * to change when a new one is added, see {@link DlrFormatter}'s class
 * Javadoc.
 */
@Component
public class DlrFormatterRegistry {

    private final Map<String, DlrFormatter> byProfile;

    public DlrFormatterRegistry(List<DlrFormatter> formatters) {
        Map<String, DlrFormatter> map = new LinkedHashMap<>();
        for (DlrFormatter formatter : formatters) {
            map.put(formatter.profileId().toLowerCase(), formatter);
        }
        this.byProfile = map;
    }

    public Optional<DlrFormatter> find(String profile) {
        if (profile == null || profile.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(byProfile.get(profile.toLowerCase()));
    }
}
