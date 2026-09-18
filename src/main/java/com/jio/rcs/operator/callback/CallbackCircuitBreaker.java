package com.jio.rcs.operator.callback;

import com.jio.rcs.operator.config.ProviderProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Per-destination (per callback URL) circuit breaker sitting in front of
 * {@link CallbackClient}'s actual HTTP call - see
 * {@link ProviderProperties.CircuitBreaker}'s Javadoc for the "why" (a
 * persistently-dead destination otherwise keeps consuming pooled
 * connections and worker time on every retry, forever, with nothing to
 * stop it since the CALLBACK queue itself is deliberately unbounded).
 *
 * <p>Standard three-state breaker (CLOSED -&gt; OPEN -&gt; HALF_OPEN -&gt; CLOSED
 * or back to OPEN), represented without an explicit enum: {@code openedAt}
 * doubles as the state flag (0 = CLOSED, non-zero = OPEN or, once cooldown
 * has elapsed, eligible for a HALF_OPEN probe) so the whole thing is three
 * small atomics per destination and never a lock. Cardinality is bounded by
 * the number of distinct callback URLs actually configured/used (tenants x
 * provider profiles) - a handful to a few hundred in practice, never
 * per-message - so the backing map itself can't become a growth problem the
 * way an unbounded per-message structure would.
 *
 * <p><b>Only one probe in flight per destination at a time.</b> Once
 * cooldown has elapsed, many concurrent callers can race to be the one that
 * tests recovery; {@code probeInFlight}'s compare-and-set ensures exactly
 * one of them is allowed through as the HALF_OPEN probe while every other
 * concurrent caller is still told to skip - otherwise a recovering-but-
 * still-fragile destination could get hit with a full burst the instant
 * cooldown expires, which is exactly the kind of load a breaker exists to
 * prevent.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CallbackCircuitBreaker {

    private final ProviderProperties providerProperties;

    private final ConcurrentMap<String, DestinationState> destinations = new ConcurrentHashMap<>();

    /** Total attempts skipped (never reached the network) because the breaker was open for that URL - see GET /metrics. */
    private final LongAdder skippedCount = new LongAdder();

    private static final class DestinationState {
        final AtomicInteger consecutiveFailures = new AtomicInteger(0);
        /** 0 = CLOSED. Non-zero = the epoch millis the breaker tripped OPEN. */
        final AtomicLong openedAtMillis = new AtomicLong(0);
        /** True while one caller is currently acting as the HALF_OPEN probe for this destination. */
        final AtomicBoolean probeInFlight = new AtomicBoolean(false);
    }

    /**
     * Call before attempting delivery. Returns true if the attempt should
     * proceed (CLOSED, or this caller won the race to be the HALF_OPEN
     * probe) - false if it should be skipped without touching the network
     * (still OPEN, cooldown not yet elapsed, or another caller already owns
     * the current probe).
     */
    public boolean allowRequest(String callbackUrl) {
        var config = providerProperties.getCallback().getCircuitBreaker();
        if (!config.isEnabled()) {
            return true;
        }

        DestinationState state = destinations.computeIfAbsent(callbackUrl, u -> new DestinationState());
        long openedAt = state.openedAtMillis.get();
        if (openedAt == 0) {
            return true; // CLOSED - normal traffic
        }

        long elapsed = System.currentTimeMillis() - openedAt;
        if (elapsed < config.getCoolDownMillis()) {
            skippedCount.increment();
            return false; // still OPEN
        }

        // Cooldown elapsed - at most one concurrent caller gets to be the
        // HALF_OPEN probe; everyone else still gets skipped until that
        // probe's result (recordResult below) either closes the breaker or
        // reopens it with a fresh cooldown window.
        boolean wonProbe = state.probeInFlight.compareAndSet(false, true);
        if (!wonProbe) {
            skippedCount.increment();
        }
        return wonProbe;
    }

    /** Call after every attempt this breaker allowed through, with whether it actually succeeded. */
    public void recordResult(String callbackUrl, boolean success) {
        var config = providerProperties.getCallback().getCircuitBreaker();
        if (!config.isEnabled()) {
            return;
        }

        // computeIfAbsent, not get(): in normal production flow allowRequest()
        // always runs first and already creates this destination's entry, but
        // recordResult() must not silently no-op if it's ever called without
        // that precondition holding (it previously did - a real bug, caught by
        // CallbackCircuitBreakerTest calling recordResult() directly to seed
        // failures without a preceding allowRequest()). Robust to call order
        // either way now, at the cost of nothing - computeIfAbsent behaves
        // identically to get() whenever the entry already exists.
        DestinationState state = destinations.computeIfAbsent(callbackUrl, u -> new DestinationState());

        if (success) {
            boolean wasOpen = state.openedAtMillis.get() != 0;
            state.consecutiveFailures.set(0);
            state.openedAtMillis.set(0);
            state.probeInFlight.set(false);
            if (wasOpen) {
                log.warn("Circuit breaker CLOSED for {} - destination recovered", callbackUrl);
            }
            return;
        }

        boolean wasProbe = state.probeInFlight.compareAndSet(true, false);
        if (wasProbe) {
            // The HALF_OPEN probe itself failed - destination is still down.
            // Reopen with a fresh cooldown window rather than leaving the
            // stale openedAt in place, so the next probe is genuinely
            // coolDownMillis away, not immediately eligible again.
            state.openedAtMillis.set(System.currentTimeMillis());
            log.debug("Circuit breaker probe failed for {} - staying OPEN for another {}ms",
                    callbackUrl, config.getCoolDownMillis());
            return;
        }

        int failures = state.consecutiveFailures.incrementAndGet();
        if (failures >= config.getFailureThreshold() && state.openedAtMillis.compareAndSet(0, System.currentTimeMillis())) {
            log.warn("Circuit breaker OPEN for {} after {} consecutive failures - skipping attempts for {}ms before the next recovery probe",
                    callbackUrl, failures, config.getCoolDownMillis());
        }
    }

    /** Backing GET /metrics' runtime.callbackCircuitBreakerSkippedCount - see RuntimeMetricsRecorder. */
    public long skippedCount() {
        return skippedCount.sum();
    }

    /** Backing GET /metrics' runtime.circuitBreakerOpenDestinationCount. */
    public long openDestinationCount() {
        return destinations.values().stream().filter(s -> s.openedAtMillis.get() != 0).count();
    }
}
