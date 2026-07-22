package com.jio.rcs.operator.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MessageStatusResponse {
    private String providerMessageId;
    private String correlationId;
    private String agentId;
    private String phoneNumber;
    private String status;
    private String errorCode;
    private String errorDescription;
    private Instant acceptedAt;
    private Instant lastUpdatedAt;
    private List<StatusHistoryEntryResponse> history;
}
