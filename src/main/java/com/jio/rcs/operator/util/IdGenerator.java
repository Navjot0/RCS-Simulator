package com.jio.rcs.operator.util;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * Generates provider-style identifiers, e.g. Provider Message IDs in the
 * form JIOxxxxxxxxxxxx, and batch ids for bulk submissions.
 */
public final class IdGenerator {

    private static final String ALPHANUMERIC = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private IdGenerator() {
    }

    public static String providerMessageId(String providerCode) {
        return providerCode + randomAlphanumeric(12);
    }

    public static String batchId(String providerCode) {
        return providerCode + "BATCH" + randomAlphanumeric(10);
    }

    public static String correlationId() {
        return UUID.randomUUID().toString();
    }

    /** Our own internal message identifier - a plain UUID, distinct from the provider-prefixed providerMessageId. */
    public static String internalMessageId() {
        return UUID.randomUUID().toString();
    }

    public static String mediaId() {
        return "MEDIA" + randomAlphanumeric(16);
    }

    private static String randomAlphanumeric(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(ALPHANUMERIC.charAt(RANDOM.nextInt(ALPHANUMERIC.length())));
        }
        return sb.toString();
    }
}
