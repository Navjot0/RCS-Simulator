package com.jio.rcs.operator.controller;

import com.jio.rcs.operator.registry.MediaStore;
import com.jio.rcs.operator.registry.MessageStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Operational escape hatch for reclaiming memory without a full process/
 * container restart. MessageStore and MediaStore are the two structures
 * that actually accumulate over a long high-TPS run (see their own class
 * Javadocs) - everything else in the pipeline (queues, the timing wheel,
 * metrics counters) is already bounded and self-cleaning, so clearing just
 * these two is equivalent to what a restart actually buys you memory-wise,
 * without the downtime or dropped connections a real restart causes.
 *
 * <p>Deliberately NOT wired into the same TPS/queue pipeline as
 * POST /v1/messages - this never touches operator.tps.limit or any queue,
 * so calling it has zero effect on throughput/TPS numbers you're measuring
 * elsewhere.
 *
 * <p>This is a destructive operation and this simulator has no
 * authentication anywhere else, so it's guarded by an optional shared
 * secret (operator.admin.reset-secret) rather than being wide open by
 * default the way every other endpoint is - set it if this simulator is
 * reachable from anywhere you don't fully trust. Leave it unset (default)
 * to keep the same no-auth posture as the rest of the app.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class AdminController {

    private final MessageStore messageStore;
    private final MediaStore mediaStore;

    @Value("${operator.admin.reset-secret:}")
    private String configuredResetSecret;

    /**
     * Call this BETWEEN load-test runs, not during live traffic. Clearing
     * these maps while requests are in flight means any message accepted
     * before this call 404s on a later GET /v1/messages/{id}, and any
     * pending DLR/callback work still referencing it silently no-ops (logs
     * a "not found" WARN) rather than crashing - see MessageStore.clear()'s
     * Javadoc.
     */
    @PostMapping("/admin/reset")
    public ResponseEntity<Map<String, Object>> reset(
            @RequestHeader(value = "X-Admin-Secret", required = false) String providedSecret) {

        if (configuredResetSecret != null && !configuredResetSecret.isBlank()
                && !configuredResetSecret.equals(providedSecret)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        int messagesCleared = messageStore.size();
        int mediaCleared = mediaStore.size();
        messageStore.clear();
        mediaStore.clear();

        log.warn("Admin reset triggered: cleared {} in-memory messages and {} media blobs "
                + "(queues/scheduler/metrics untouched - already bounded, see AdminController Javadoc)",
                messagesCleared, mediaCleared);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "ok");
        body.put("messagesCleared", messagesCleared);
        body.put("mediaCleared", mediaCleared);
        return ResponseEntity.ok(body);
    }
}
