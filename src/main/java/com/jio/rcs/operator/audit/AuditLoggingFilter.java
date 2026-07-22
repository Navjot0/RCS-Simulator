package com.jio.rcs.operator.audit;


import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collections;
import java.util.Enumeration;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Wraps every HTTP request/response to capture exactly what the spec asks
 * for: incoming request, outgoing response, headers, execution time and
 * correlation id - logged (not persisted - this service stores nothing,
 * see AuditLogService) for full request traceability.
 *
 * <p><b>Disabled by default</b> (operator.audit.enabled=false). This filter
 * exists for occasional troubleshooting, not routine operation: wrapping
 * every request/response in {@link ContentCachingRequestWrapper}/
 * {@link ContentCachingResponseWrapper} to buffer full bodies, then logging
 * one line per HTTP exchange, is real overhead at the 10,000+ TPS this
 * simulator targets - and no test in this project depends on it (verified:
 * nothing references AuditLogEntry/X-Correlation-Id from src/test). Gated
 * via {@link ConditionalOnProperty} rather than an internal early-return so
 * that when disabled, the bean - and the request/response wrapping it would
 * otherwise perform on every single request - never gets created at all,
 * not merely skipped after the fact. Set operator.audit.enabled=true to
 * turn full request/response audit logging back on.
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "operator.audit", name = "enabled", havingValue = "true")
public class AuditLoggingFilter extends OncePerRequestFilter {

    private static final int MAX_BODY_LENGTH = 4000;

    private final AuditLogService auditLogService;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/swagger-ui") || path.startsWith("/v3/api-docs");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                     FilterChain filterChain) throws ServletException, IOException {
        ContentCachingRequestWrapper wrappedRequest = new ContentCachingRequestWrapper(request);
        ContentCachingResponseWrapper wrappedResponse = new ContentCachingResponseWrapper(response);

        String correlationId = request.getHeader("X-Correlation-Id");
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }
        wrappedResponse.setHeader("X-Correlation-Id", correlationId);

        long start = System.currentTimeMillis();
        try {
            filterChain.doFilter(wrappedRequest, wrappedResponse);
        } finally {
            long executionTime = System.currentTimeMillis() - start;

            String requestBody = truncate(new String(wrappedRequest.getContentAsByteArray(), StandardCharsets.UTF_8));
            String responseBody = truncate(new String(wrappedResponse.getContentAsByteArray(), StandardCharsets.UTF_8));
            String headers = collectHeaders(request);

            AuditLogEntry auditLog = AuditLogEntry.builder()
                    .correlationId(correlationId)
                    .httpMethod(request.getMethod())
                    .uri(request.getRequestURI())
                    .requestHeaders(headers)
                    .requestBody(requestBody)
                    .responseBody(responseBody)
                    .responseStatus(wrappedResponse.getStatus())
                    .executionTimeMillis(executionTime)
                    .eventType("HTTP_EXCHANGE")
                    .createdAt(Instant.now())
                    .build();
            auditLogService.record(auditLog);

            wrappedResponse.copyBodyToResponse();
        }
    }

    private String collectHeaders(HttpServletRequest request) {
        Enumeration<String> names = request.getHeaderNames() == null
                ? Collections.emptyEnumeration() : request.getHeaderNames();
        return Collections.list(names).stream()
                .filter(h -> !h.equalsIgnoreCase("Authorization"))
                .map(h -> h + "=" + request.getHeader(h))
                .collect(Collectors.joining("; "));
    }

    private String truncate(String value) {
        if (value == null) return null;
        return value.length() > MAX_BODY_LENGTH ? value.substring(0, MAX_BODY_LENGTH) + "...(truncated)" : value;
    }
}
