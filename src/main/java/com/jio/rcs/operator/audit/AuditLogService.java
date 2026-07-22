package com.jio.rcs.operator.audit;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Emits a structured audit log line per HTTP exchange. Deliberately does
 * NOT persist anything - this service stores nothing, so "audit logging"
 * means exactly that: a log line (which an operator can ship to whatever
 * log aggregation they already run), not a database table.
 *
 * <p>Only ever called from {@link AuditLoggingFilter}, which is disabled by
 * default (operator.audit.enabled=false) - so by default this method simply
 * never runs. Kept as its own bean/class regardless of that filter's state
 * since it's the natural place to add real audit-log shipping later
 * (e.g. to a file, a queue, or a log aggregator) without touching the
 * filter itself.
 */
@Slf4j
@Service
public class AuditLogService {

    @Async("auditExecutor")
    public void record(AuditLogEntry entry) {
        log.info("AUDIT correlationId={} method={} uri={} status={} executionTimeMs={} eventType={} headers=[{}] "
                        + "requestBody={} responseBody={}",
                entry.getCorrelationId(), entry.getHttpMethod(), entry.getUri(), entry.getResponseStatus(),
                entry.getExecutionTimeMillis(), entry.getEventType(), entry.getRequestHeaders(),
                entry.getRequestBody(), entry.getResponseBody());
    }
}
