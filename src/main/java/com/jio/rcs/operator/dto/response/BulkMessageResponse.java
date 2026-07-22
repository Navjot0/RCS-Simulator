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
public class BulkMessageResponse {
    private String batchId;
    private int acceptedCount;
    private int rejectedCount;
    private Instant timestamp;
    private List<BulkMessageResultItem> results;
}
