package com.jio.rcs.operator.util;

import java.security.SecureRandom;
import java.util.List;

/**
 * Weighted-random helpers backing the provider's probability engine
 * (delivered/displayed/failed percentages) and error-code simulation.
 */
public final class ProbabilityUtil {

    private static final SecureRandom RANDOM = new SecureRandom();

    private ProbabilityUtil() {
    }

    /** Returns true with the given percentage chance (0-100). */
    public static boolean chance(int percentage) {
        if (percentage <= 0) return false;
        if (percentage >= 100) return true;
        return RANDOM.nextInt(100) < percentage;
    }

    /** Picks one item from a weighted list. Falls back to uniform pick if all weights are zero. */
    public static <T> T weightedPick(List<T> items, java.util.function.ToIntFunction<T> weightFn) {
        int total = items.stream().mapToInt(weightFn).sum();
        if (total <= 0) {
            return items.get(RANDOM.nextInt(items.size()));
        }
        int r = RANDOM.nextInt(total);
        int cumulative = 0;
        for (T item : items) {
            cumulative += weightFn.applyAsInt(item);
            if (r < cumulative) {
                return item;
            }
        }
        return items.get(items.size() - 1);
    }

    public static long randomBetween(long min, long max) {
        if (max <= min) return min;
        return min + (long) (RANDOM.nextDouble() * (max - min));
    }
}
