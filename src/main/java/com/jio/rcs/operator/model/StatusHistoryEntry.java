package com.jio.rcs.operator.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StatusHistoryEntry {
    private String previousStatus;
    private String newStatus;
    private String errorCode;
    private String errorDescription;
    private Instant transitionAt;
}
