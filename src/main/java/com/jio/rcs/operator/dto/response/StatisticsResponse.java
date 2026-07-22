package com.jio.rcs.operator.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StatisticsResponse {
    private long totalMessages;
    private Map<String, Long> countByStatus;
    private long totalCallbacks;
    private long callbacksDelivered;
    private long callbacksDeadLettered;
}
