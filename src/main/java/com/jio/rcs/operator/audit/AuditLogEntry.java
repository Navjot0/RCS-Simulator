package com.jio.rcs.operator.audit;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * A single audited HTTP exchange. This is a plain, transient value object -
 * it is logged via SLF4J (see AuditLogService) and then discarded. Nothing
 * about a request/response is ever written to a database or disk.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditLogEntry {
    private String correlationId;
    private String httpMethod;
    private String uri;
    private String requestHeaders;
    private String requestBody;
    private String responseBody;
    private Integer responseStatus;
    private Long executionTimeMillis;
    private String eventType;
    private Instant createdAt;
}
