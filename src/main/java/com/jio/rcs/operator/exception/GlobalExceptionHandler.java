package com.jio.rcs.operator.exception;

import com.jio.rcs.operator.dto.response.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.List;

/**
 * Central exception -> HTTP response translation so every controller
 * returns a consistent error envelope, the way a real operator API would.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * Handled separately from the generic {@link ProviderException} case
     * below (and matched first - Spring picks the most specific
     * {@code @ExceptionHandler} for a given thrown type) purely for its log
     * level. A 429 here is {@code operator.tps.limit} doing exactly its job
     * under offered load above the configured ceiling - under a real load
     * test that's not an occasional/noteworthy event, it's the dominant
     * outcome by volume (seen firing on multiple tomcat-handler threads
     * within the same millisecond). Logging every single one at WARN was
     * real per-request overhead (formatting + enqueueing a log line, even
     * via the AsyncAppender) multiplied across however many hundreds of
     * thousands of rejections a test generates, on a box that's already
     * CPU-constrained - contributing to load instead of just reporting it.
     * DEBUG keeps it available for troubleshooting (set
     * logging.level.com.jio.rcs.operator=DEBUG) without paying that cost by
     * default. The rejection itself (HTTP 429 response) is unchanged.
     */
    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ErrorResponse> handleRateLimitExceeded(RateLimitExceededException ex, HttpServletRequest request) {
        log.debug("Provider exception [{}]: {}", ex.getErrorCode(), ex.getMessage());
        return providerExceptionResponse(ex, request);
    }

    @ExceptionHandler(ProviderException.class)
    public ResponseEntity<ErrorResponse> handleProviderException(ProviderException ex, HttpServletRequest request) {
        log.warn("Provider exception [{}]: {}", ex.getErrorCode(), ex.getMessage());
        return providerExceptionResponse(ex, request);
    }

    private ResponseEntity<ErrorResponse> providerExceptionResponse(ProviderException ex, HttpServletRequest request) {
        ErrorResponse body = ErrorResponse.builder()
                .timestamp(Instant.now())
                .status(ex.getHttpStatus().value())
                .error(ex.getErrorCode())
                .message(ex.getMessage())
                .path(request.getRequestURI())
                .build();
        return ResponseEntity.status(ex.getHttpStatus()).body(body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        List<String> details = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .toList();
        ErrorResponse body = ErrorResponse.builder()
                .timestamp(Instant.now())
                .status(HttpStatus.BAD_REQUEST.value())
                .error("VALIDATION_FAILED")
                .message("Request payload failed validation")
                .path(request.getRequestURI())
                .details(details)
                .build();
        return ResponseEntity.badRequest().body(body);
    }

    /**
     * The request body doesn't even parse as valid JSON for this DTO -
     * wrong type for a field (e.g. an array where a string is expected),
     * an unrecognized enum literal, malformed JSON syntax, etc. Previously
     * unhandled and fell through to {@link #handleGeneric}, returning a
     * confusing 500 for what is really a client-side bad-request problem;
     * now reported as a clear 400 instead.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadableMessage(HttpMessageNotReadableException ex, HttpServletRequest request) {
        log.warn("Malformed request body for {} {}: {}", request.getMethod(), request.getRequestURI(), ex.getMessage());
        ErrorResponse body = ErrorResponse.builder()
                .timestamp(Instant.now())
                .status(HttpStatus.BAD_REQUEST.value())
                .error("VALIDATION_FAILED")
                .message("Request body could not be parsed - check field types against the API docs "
                        + "(e.g. to must be a JSON array of strings, not a bare string; content can be any JSON "
                        + "value - object, array, string, number, boolean, or null)")
                .path(request.getRequestURI())
                .build();
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex, HttpServletRequest request) {
        ErrorResponse body = ErrorResponse.builder()
                .timestamp(Instant.now())
                .status(HttpStatus.BAD_REQUEST.value())
                .error("BAD_REQUEST")
                .message(ex.getMessage())
                .path(request.getRequestURI())
                .build();
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception processing {} {}", request.getMethod(), request.getRequestURI(), ex);
        ErrorResponse body = ErrorResponse.builder()
                .timestamp(Instant.now())
                .status(HttpStatus.INTERNAL_SERVER_ERROR.value())
                .error("SERVICE_UNAVAILABLE")
                .message("Unexpected error in provider simulator")
                .path(request.getRequestURI())
                .build();
        return ResponseEntity.internalServerError().body(body);
    }
}
