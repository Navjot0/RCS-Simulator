package com.jio.rcs.operator.unit;

import com.jio.rcs.operator.config.ProviderProperties;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the Bean Validation constraints on ProviderProperties.Queue's
 * per-stage worker fields (operator.queue.*-workers) behave exactly as
 * requirement 3 asks: reject 0/negative, reject unreasonably large values
 * (e.g. 50000), and leave "unset" (null - meaning "fall back to
 * worker-threads/4") untouched. Runs Jakarta Bean Validation directly
 * (Hibernate Validator, already on the classpath via
 * spring-boot-starter-validation) rather than booting a Spring context -
 * this is exactly the same validation Spring Boot runs automatically at
 * startup via @Validated on ProviderProperties, just exercised without the
 * overhead of a full ApplicationContext.
 */
class ProviderPropertiesValidationTest {

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void tearDownValidator() {
        validatorFactory.close();
    }

    private ProviderProperties validProperties() {
        // Defaults out of the box must always be valid - if this ever fails,
        // the shipped application.properties defaults would fail startup.
        return new ProviderProperties();
    }

    @Test
    void defaultsAreValid() {
        assertThat(validator.validate(validProperties())).isEmpty();
    }

    @Test
    void unsetPerStageWorkerCountsAreValid() {
        ProviderProperties properties = validProperties();
        properties.getQueue().setIncomingWorkers(null);
        properties.getQueue().setCallbackWorkers(null);

        assertThat(validator.validate(properties)).isEmpty();
    }

    @Test
    void rejectsZeroCallbackWorkers() {
        ProviderProperties properties = validProperties();
        properties.getQueue().setCallbackWorkers(0);

        Set<ConstraintViolation<ProviderProperties>> violations = validator.validate(properties);

        assertThat(violations).isNotEmpty();
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("queue.callbackWorkers"));
    }

    @Test
    void rejectsNegativeDlrWorkers() {
        ProviderProperties properties = validProperties();
        properties.getQueue().setDlrWorkers(-1);

        Set<ConstraintViolation<ProviderProperties>> violations = validator.validate(properties);

        assertThat(violations).isNotEmpty();
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("queue.dlrWorkers"));
    }

    @Test
    void rejectsUnreasonablyLargeProcessingWorkers() {
        ProviderProperties properties = validProperties();
        properties.getQueue().setProcessingWorkers(50_000);

        Set<ConstraintViolation<ProviderProperties>> violations = validator.validate(properties);

        assertThat(violations).isNotEmpty();
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("queue.processingWorkers"));
    }

    @Test
    void acceptsEveryStageIndependentlyConfiguredWithinRange() {
        ProviderProperties properties = validProperties();
        properties.getQueue().setIncomingWorkers(16);
        properties.getQueue().setValidationWorkers(8);
        properties.getQueue().setProcessingWorkers(32);
        properties.getQueue().setDlrWorkers(64);
        properties.getQueue().setCallbackWorkers(128);

        assertThat(validator.validate(properties)).isEmpty();
    }

    @Test
    void rejectsZeroWorkerThreadsFallbackBase() {
        ProviderProperties properties = validProperties();
        properties.getQueue().setWorkerThreads(0);

        assertThat(validator.validate(properties)).isNotEmpty();
    }

    @Test
    void rejectsZeroSchedulerWorkerCount() {
        ProviderProperties properties = validProperties();
        properties.getScheduler().setWorkerCount(0);

        Set<ConstraintViolation<ProviderProperties>> violations = validator.validate(properties);

        assertThat(violations).isNotEmpty();
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("scheduler.workerCount"));
    }
}
