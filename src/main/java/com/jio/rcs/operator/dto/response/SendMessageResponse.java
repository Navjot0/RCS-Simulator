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
public class SendMessageResponse {
    private String status;
    private String providerMessageId;
    private String correlationId;
    private Instant timestamp;
}
