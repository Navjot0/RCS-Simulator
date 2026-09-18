package com.jio.rcs.operator.unit;

import com.jio.rcs.operator.callback.CallbackCircuitBreaker;
import com.jio.rcs.operator.config.ProviderProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves CallbackCircuitBreaker's state machine: stays CLOSED under
 * isolated failures below the threshold, trips OPEN once consecutive
 * failures hit it (and stays OPEN, skipping every attempt, until cooldown
 * elapses), allows exactly one HALF_OPEN probe through once cooldown has
 * passed, and closes again on that probe's success - or reopens with a
 * fresh cooldown window if the probe itself fails.
 */
class CallbackCircuitBreakerTest {

    private static final String URL = "https://dead-receiver.example.com/webhook";

    private CallbackCircuitBreaker newBreaker(int failureThreshold, long coolDownMillis) {
        ProviderProperties properties = new ProviderProperties();
        ProviderProperties.CircuitBreaker config = new ProviderProperties.CircuitBreaker();
        config.setEnabled(true);
        config.setFailureThreshold(failureThreshold);
        config.setCoolDownMillis(coolDownMillis);
        ProviderProperties.Callback callback = new ProviderProperties.Callback();
        callback.setCircuitBreaker(config);
        properties.setCallback(callback);
        return new CallbackCircuitBreaker(properties);
    }

    @Test
    void staysClosedBelowFailureThreshold() {
        CallbackCircuitBreaker breaker = newBreaker(3, 30_000);

        assertThat(breaker.allowRequest(URL)).isTrue();
        breaker.recordResult(URL, false);
        assertThat(breaker.allowRequest(URL)).isTrue();
        breaker.recordResult(URL, false);

        // Only 2 consecutive failures recorded - below the threshold of 3 -
        // still CLOSED, every request still allowed through.
        assertThat(breaker.allowRequest(URL)).isTrue();
        assertThat(breaker.openDestinationCount()).isZero();
    }

    @Test
    void aSuccessResetsTheConsecutiveFailureCount() {
        CallbackCircuitBreaker breaker = newBreaker(3, 30_000);

        breaker.recordResult(URL, false);
        breaker.recordResult(URL, false);
        breaker.recordResult(URL, true); // resets to 0
        breaker.recordResult(URL, false);
        breaker.recordResult(URL, false);

        // 2 consecutive failures since the reset - still below threshold of 3.
        assertThat(breaker.allowRequest(URL)).isTrue();
        assertThat(breaker.openDestinationCount()).isZero();
    }

    @Test
    void tripsOpenAfterConsecutiveFailuresHitTheThresholdAndSkipsFurtherAttempts() {
        CallbackCircuitBreaker breaker = newBreaker(3, 30_000);

        for (int i = 0; i < 3; i++) {
            assertThat(breaker.allowRequest(URL)).isTrue();
            breaker.recordResult(URL, false);
        }

        // Breaker is now OPEN - cooldown (30s) hasn't elapsed, so every
        // further request is skipped without touching the network.
        assertThat(breaker.allowRequest(URL)).isFalse();
        assertThat(breaker.allowRequest(URL)).isFalse();
        assertThat(breaker.openDestinationCount()).isEqualTo(1);
        assertThat(breaker.skippedCount()).isEqualTo(2);
    }

    @Test
    void allowsExactlyOneProbeOnceCooldownElapsesAndClosesOnSuccess() throws InterruptedException {
        CallbackCircuitBreaker breaker = newBreaker(2, 20);

        breaker.recordResult(URL, false);
        breaker.recordResult(URL, false);
        assertThat(breaker.allowRequest(URL)).isFalse(); // still within cooldown

        Thread.sleep(30); // let the 20ms cooldown elapse

        assertThat(breaker.allowRequest(URL)).isTrue();  // this caller wins the probe
        assertThat(breaker.allowRequest(URL)).isFalse(); // a second concurrent caller does not

        breaker.recordResult(URL, true); // probe succeeded
        assertThat(breaker.openDestinationCount()).isZero();
        assertThat(breaker.allowRequest(URL)).isTrue(); // fully CLOSED again
    }

    @Test
    void reopensWithFreshCooldownWhenTheProbeItselfFails() throws InterruptedException {
        CallbackCircuitBreaker breaker = newBreaker(1, 20);

        breaker.recordResult(URL, false); // trips open immediately (threshold=1)
        Thread.sleep(30);

        assertThat(breaker.allowRequest(URL)).isTrue(); // probe allowed through
        breaker.recordResult(URL, false); // probe failed

        // Immediately after the failed probe, still within the fresh cooldown.
        assertThat(breaker.allowRequest(URL)).isFalse();
        assertThat(breaker.openDestinationCount()).isEqualTo(1);
    }

    @Test
    void disabledBreakerAlwaysAllowsRequests() {
        ProviderProperties properties = new ProviderProperties();
        ProviderProperties.CircuitBreaker config = new ProviderProperties.CircuitBreaker();
        config.setEnabled(false);
        ProviderProperties.Callback callback = new ProviderProperties.Callback();
        callback.setCircuitBreaker(config);
        properties.setCallback(callback);
        CallbackCircuitBreaker breaker = new CallbackCircuitBreaker(properties);

        for (int i = 0; i < 50; i++) {
            assertThat(breaker.allowRequest(URL)).isTrue();
            breaker.recordResult(URL, false);
        }
        assertThat(breaker.openDestinationCount()).isZero();
        assertThat(breaker.skippedCount()).isZero();
    }

    @Test
    void tracksEachDestinationIndependently() {
        CallbackCircuitBreaker breaker = newBreaker(1, 30_000);
        String otherUrl = "https://healthy-receiver.example.com/webhook";

        breaker.recordResult(URL, false); // trips URL open
        assertThat(breaker.allowRequest(URL)).isFalse();
        assertThat(breaker.allowRequest(otherUrl)).isTrue(); // untouched, still CLOSED
        assertThat(breaker.openDestinationCount()).isEqualTo(1);
    }
}
