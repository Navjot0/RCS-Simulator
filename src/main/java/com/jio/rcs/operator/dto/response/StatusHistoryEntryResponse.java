package com.jio.rcs.operator.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StatusHistoryEntryResponse {
    private String previousStatus;
    private String newStatus;
    private String errorCode;
    private String errorDescription;
    private Instant transitionAt;
}
