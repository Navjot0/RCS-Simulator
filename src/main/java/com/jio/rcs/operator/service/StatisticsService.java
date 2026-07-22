package com.jio.rcs.operator.service;

import com.jio.rcs.operator.dto.response.StatisticsResponse;
import com.jio.rcs.operator.registry.MessageStore;
import com.jio.rcs.operator.statemachine.MessageState;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Computes statistics by scanning the in-memory MessageStore - there is no
 * separate counts table. Figures reflect only messages currently held in
 * memory (i.e. within operator.message-store.retention-minutes of reaching
 * a terminal state).
 */
@Service
@RequiredArgsConstructor
public class StatisticsService {

    private final MessageStore messageStore;

    public StatisticsResponse getStatistics() {
        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (MessageState state : MessageState.values()) {
            byStatus.put(state.name(), messageStore.byStatus(state.name()).stream().count());
        }
        long totalMessages = messageStore.size();

        long totalCallbacks = messageStore.all().stream()
                .filter(m -> !m.getCallbackAttempts().isEmpty())
                .count();
        long delivered = messageStore.all().stream()
                .filter(m -> "DELIVERED".equals(m.getCallbackStatus()))
                .count();
        long deadLettered = messageStore.all().stream()
                .filter(m -> "DEAD_LETTERED".equals(m.getCallbackStatus()))
                .count();

        return StatisticsResponse.builder()
                .totalMessages(totalMessages)
                .countByStatus(byStatus)
                .totalCallbacks(totalCallbacks)
                .callbacksDelivered(delivered)
                .callbacksDeadLettered(deadLettered)
                .build();
    }
}
