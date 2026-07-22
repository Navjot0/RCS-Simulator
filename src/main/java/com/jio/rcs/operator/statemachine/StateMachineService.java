package com.jio.rcs.operator.statemachine;

import com.jio.rcs.operator.config.ProviderProperties;
import com.jio.rcs.operator.exception.InvalidStateTransitionException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Enforces the configurable message-lifecycle state machine. Allowed
 * transitions are read from operator.state-machine.transitions so the
 * lifecycle graph can be redefined per environment without code changes.
 */
@Service
@RequiredArgsConstructor
public class StateMachineService {

    private final ProviderProperties providerProperties;

    public Set<MessageState> allowedTransitions(MessageState from) {
        Map<String, List<String>> transitions = providerProperties.getStateMachine().getTransitions();
        if (transitions == null || !transitions.containsKey(from.name())) {
            return Set.of();
        }
        return transitions.get(from.name()).stream()
                .filter(s -> s != null && !s.isBlank())
                .map(String::trim)
                .map(MessageState::valueOf)
                .collect(Collectors.toSet());
    }

    public boolean canTransition(MessageState from, MessageState to) {
        if (from == to) {
            return false;
        }
        return allowedTransitions(from).contains(to);
    }

    public void assertTransition(MessageState from, MessageState to) {
        if (!canTransition(from, to)) {
            throw new InvalidStateTransitionException(
                    "Illegal transition from " + from + " to " + to);
        }
    }
}
