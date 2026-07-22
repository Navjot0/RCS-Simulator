package com.jio.rcs.operator.unit;

import com.jio.rcs.operator.config.ProviderProperties;
import com.jio.rcs.operator.model.MessageContext;
import com.jio.rcs.operator.processor.DefaultDlrOutcomeStrategy;
import com.jio.rcs.operator.processor.DlrOutcome;
import com.jio.rcs.operator.statemachine.MessageState;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultDlrOutcomeStrategyTest {

    private MessageContext sampleMessage() {
        return MessageContext.builder()
                .providerMessageId("SIMTEST0001")
                .agentId("agent-demo-001")
                .phoneNumber("+919999999999")
                .status(MessageState.SUBMITTED.name())
                .acceptedAt(Instant.now())
                .build();
    }

    /** Builds a ProviderProperties instance carrying the given probability/error config. */
    private ProviderProperties propertiesWith(int failedPct, int deliveredPct, int displayedPct) {
        ProviderProperties.Probability probability = new ProviderProperties.Probability();
        probability.setFailedPercentage(failedPct);
        probability.setDeliveredPercentage(deliveredPct);
        probability.setDisplayedPercentage(displayedPct);

        ProviderProperties.ErrorSimulation errorSimulation = new ProviderProperties.ErrorSimulation();
        errorSimulation.setEnabled(true);
        ProviderProperties.ErrorCode code = new ProviderProperties.ErrorCode();
        code.setCode("DEVICE_OFFLINE");
        code.setDescription("Destination device is currently offline");
        code.setWeight(100);
        errorSimulation.setCodes(List.of(code));

        ProviderProperties properties = new ProviderProperties();
        properties.setProbability(probability);
        properties.setErrorSimulation(errorSimulation);
        return properties;
    }

    @Test
    void alwaysFailsWhenFailedPercentageIs100() {
        var strategy = new DefaultDlrOutcomeStrategy(propertiesWith(100, 0, 0));
        DlrOutcome outcome = strategy.decideOutcome(sampleMessage());
        assertThat(outcome.getTerminalState()).isEqualTo(MessageState.FAILED);
        assertThat(outcome.getErrorCode()).isEqualTo("DEVICE_OFFLINE");
    }

    @Test
    void deliversAndDisplaysWhenBothPercentagesAre100() {
        var strategy = new DefaultDlrOutcomeStrategy(propertiesWith(0, 100, 100));
        DlrOutcome outcome = strategy.decideOutcome(sampleMessage());
        assertThat(outcome.getTerminalState()).isEqualTo(MessageState.DELIVERED);
        assertThat(outcome.isDisplayedAfterDelivered()).isTrue();
    }

    @Test
    void deliversButDoesNotDisplayWhenDisplayedPercentageIsZero() {
        var strategy = new DefaultDlrOutcomeStrategy(propertiesWith(0, 100, 0));
        DlrOutcome outcome = strategy.decideOutcome(sampleMessage());
        assertThat(outcome.getTerminalState()).isEqualTo(MessageState.DELIVERED);
        assertThat(outcome.isDisplayedAfterDelivered()).isFalse();
    }

    @Test
    void fallsBackToUnknownWhenDeliveredPercentageIsZero() {
        var strategy = new DefaultDlrOutcomeStrategy(propertiesWith(0, 0, 0));
        DlrOutcome outcome = strategy.decideOutcome(sampleMessage());
        assertThat(outcome.getTerminalState()).isEqualTo(MessageState.UNKNOWN);
    }
}
