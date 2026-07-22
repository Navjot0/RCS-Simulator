package com.jio.rcs.operator.util;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Formats an {@link Instant} as Indian Standard Time (Asia/Kolkata, UTC+05:30)
 * with an explicit offset (e.g. {@code 2026-07-21T18:17:46.867122+05:30})
 * instead of the JVM/UTC default.
 *
 * <p>Every timestamp field that's a typed {@code Instant} on a DTO (the vast
 * majority - see {@code CallbackEnvelope}, {@code DeliveryInfo}, {@code
 * MessageStatusResponse}, etc.) already renders in IST automatically via the
 * {@code spring.jackson.time-zone=Asia/Kolkata} property in
 * application.properties - no code needs to call this class for those.
 *
 * <p>This helper exists only for the handful of places that build a
 * timestamp as a plain {@code String} instead of leaving it as a typed
 * {@code Instant} for Jackson to serialize (e.g. {@code
 * CallbackEngine}'s {@code webhook_data.entity.sendTime}, and {@code
 * MonitoringController}'s {@code HealthResponse.timestamp}) - those bypass
 * Jackson entirely, so the global time-zone property has no effect on them.
 */
public final class IstTime {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private IstTime() {
    }

    public static String format(Instant instant) {
        return instant.atZone(IST).format(FORMATTER);
    }

    public static String now() {
        return format(Instant.now());
    }
}
