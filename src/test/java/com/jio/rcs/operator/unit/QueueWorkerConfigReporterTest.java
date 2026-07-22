package com.jio.rcs.operator.unit;

import com.jio.rcs.operator.config.ProviderProperties;
import com.jio.rcs.operator.config.QueueWorkerConfigReporter;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises QueueWorkerConfigReporter's connection-pool cross-check as a
 * pure predicate (requirement 7: warn, never fail startup, when
 * callback-workers exceeds operator.callback.max-connections-per-route).
 * The predicate is package-private specifically so it's testable without
 * capturing SLF4J log output - see that class's Javadoc.
 */
class QueueWorkerConfigReporterTest {

    private final QueueWorkerConfigReporter reporter = new QueueWorkerConfigReporter(new ProviderProperties());

    @Test
    void flagsWhenCallbackWorkersExceedThePool() {
        assertThat(reporter.callbackWorkersExceedConnectionPool(256, 100)).isTrue();
    }

    @Test
    void doesNotFlagWhenCallbackWorkersAreWithinThePool() {
        assertThat(reporter.callbackWorkersExceedConnectionPool(64, 100)).isFalse();
    }

    @Test
    void doesNotFlagWhenCallbackWorkersExactlyMatchThePool() {
        // Exactly matching the pool size is fine - every callback worker can
        // get a connection simultaneously; only *exceeding* it means some
        // workers would have to wait.
        assertThat(reporter.callbackWorkersExceedConnectionPool(100, 100)).isFalse();
    }
}
