package com.jio.rcs.operator.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BulkMessageResultItem {
    private String correlationId;
    private String providerMessageId;
    private String status;
    private String errorCode;
    private String errorDescription;
}
