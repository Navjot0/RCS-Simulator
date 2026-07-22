package com.jio.rcs.operator.unit;

import com.jio.rcs.operator.config.ProviderProperties;
import com.jio.rcs.operator.statemachine.MessageState;
import com.jio.rcs.operator.statemachine.StateMachineService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StateMachineServiceTest {

    private StateMachineService stateMachineService;

    @BeforeEach
    void setUp() {
        ProviderProperties properties = new ProviderProperties();
        ProviderProperties.StateMachine stateMachine = new ProviderProperties.StateMachine();
        stateMachine.setTransitions(Map.of(
                "ACCEPTED", List.of("QUEUED", "REJECTED"),
                "QUEUED", List.of("SUBMITTED", "FAILED", "EXPIRED"),
                "SUBMITTED", List.of("DELIVERED", "FAILED", "UNKNOWN", "EXPIRED"),
                "DELIVERED", List.of("DISPLAYED", "UNKNOWN"),
                "DISPLAYED", List.of(),
                "FAILED", List.of(),
                "REJECTED", List.of(),
                "EXPIRED", List.of(),
                "UNKNOWN", List.of()
        ));
        properties.setStateMachine(stateMachine);
        stateMachineService = new StateMachineService(properties);
    }

    @Test
    void allowsConfiguredHappyPathTransitions() {
        assertThat(stateMachineService.canTransition(MessageState.ACCEPTED, MessageState.QUEUED)).isTrue();
        assertThat(stateMachineService.canTransition(MessageState.QUEUED, MessageState.SUBMITTED)).isTrue();
        assertThat(stateMachineService.canTransition(MessageState.SUBMITTED, MessageState.DELIVERED)).isTrue();
        assertThat(stateMachineService.canTransition(MessageState.DELIVERED, MessageState.DISPLAYED)).isTrue();
    }

    @Test
    void rejectsIllegalTransitions() {
        assertThat(stateMachineService.canTransition(MessageState.ACCEPTED, MessageState.DELIVERED)).isFalse();
        assertThat(stateMachineService.canTransition(MessageState.DISPLAYED, MessageState.ACCEPTED)).isFalse();
    }

    @Test
    void terminalStatesHaveNoOutboundTransitions() {
        assertThat(stateMachineService.allowedTransitions(MessageState.DISPLAYED)).isEmpty();
        assertThat(stateMachineService.allowedTransitions(MessageState.FAILED)).isEmpty();
    }

    @Test
    void sameStateTransitionIsNeverAllowed() {
        assertThat(stateMachineService.canTransition(MessageState.ACCEPTED, MessageState.ACCEPTED)).isFalse();
    }
}
