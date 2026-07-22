package com.jio.rcs.operator.model;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory, transient representation of a single in-flight (or recently
 * completed) RCS message. This is the ONLY place message state lives -
 * there is no database. An instance exists purely so the asynchronous
 * pipeline stages can correlate work back to the original request and so
 * GET /v1/messages/{id} can answer a status query; it is discarded by
 * {@code MessageStoreCleanupScheduler} once its retention window elapses,
 * and everything is lost on restart, same as a stateless provider edge
 * service.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MessageContext {

    private String providerMessageId;

    /**
     * Our own internal message identifier (a UUID, generated once at
     * ingestion by MessageProcessor) - distinct from providerMessageId,
     * which is the provider's own id. Populates the DLR webhook's top-level
     * {@code message_id} and {@code message.id} fields.
     */
    private String internalMessageId;

    private String correlationId;
    private String batchId;
    /** Human-readable provider identity that handled this message - operator.identity.provider-name. */
    private String providerName;
    /**
     * Whatever the caller sent as agentId - this simulator is open/
     * unauthenticated (see README), so there's no registered-agent concept
     * to validate it against; it's echoed straight back into the DLR
     * webhook's agent.id/agent.name fields.
     */
    private String agentId;
    private String phoneNumber;
    private String messageType;
    /**
     * The caller's opaque, dynamic content JSON, echoed verbatim into the
     * DLR/dispatch webhook body - the simulator never inspects or validates
     * its internal shape (see SendMessageRequest javadoc / README "Content
     * model"). Replaces the earlier per-type typed fields (richCard/
     * carousel/media/suggestions/messageContent).
     */
    private JsonNode content;

    private volatile String status;
    private volatile String errorCode;
    private volatile String errorDescription;

    private Instant acceptedAt;
    private volatile Instant lastUpdatedAt;
    private volatile String callbackUrl;
    private volatile String callbackStatus;

    @Builder.Default
    private List<StatusHistoryEntry> history = new CopyOnWriteArrayList<>();

    @Builder.Default
    private List<CallbackAttempt> callbackAttempts = new CopyOnWriteArrayList<>();

    /**
     * The remaining steps of this message's precomputed DLR lifecycle plan
     * (see {@link PlannedDlrTransition}), in order. DlrEngine polls one
     * entry at a time and only schedules the next one once the current one
     * has been confirmed applied (by DlrQueueConsumer) - never all of them
     * up front against the wall clock - so a transition can never be
     * attempted before the one it causally depends on has actually landed,
     * even under scheduling backpressure. Empty once the lifecycle
     * completes (or for a message that was REJECTED before ever reaching
     * this plan).
     */
    @Builder.Default
    private Queue<PlannedDlrTransition> pendingDlrTransitions = new ConcurrentLinkedQueue<>();

    /** Atomically records a lifecycle transition (used by the DLR queue consumer). */
    public synchronized void applyTransition(String newStatus, String errCode, String errDesc) {
        String previous = this.status;
        this.history.add(StatusHistoryEntry.builder()
                .previousStatus(previous)
                .newStatus(newStatus)
                .errorCode(errCode)
                .errorDescription(errDesc)
                .transitionAt(Instant.now())
                .build());
        this.status = newStatus;
        this.errorCode = errCode;
        this.errorDescription = errDesc;
        this.lastUpdatedAt = Instant.now();
    }

    public void recordCallbackAttempt(CallbackAttempt attempt) {
        this.callbackAttempts.add(attempt);
    }

    public boolean isTerminal(java.util.function.Predicate<String> terminalCheck) {
        return status != null && terminalCheck.test(status);
    }
}
