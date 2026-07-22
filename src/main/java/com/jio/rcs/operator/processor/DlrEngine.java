package com.jio.rcs.operator.processor;

import com.jio.rcs.operator.config.ProviderProperties;
import com.jio.rcs.operator.model.MessageContext;
import com.jio.rcs.operator.model.PlannedDlrTransition;
import com.jio.rcs.operator.queue.QueueMessage;
import com.jio.rcs.operator.queue.QueueNames;
import com.jio.rcs.operator.queue.QueueService;
import com.jio.rcs.operator.scheduler.DlrScheduler;
import com.jio.rcs.operator.statemachine.MessageState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The DLR Generator. Given a message that has passed validation, plans its
 * entire remaining lifecycle (QUEUED -&gt; SUBMITTED -&gt; terminal state
 * [-&gt; DISPLAYED]) using the configurable per-state delays in
 * operator.dlr.delays-seconds, all measured relative to
 * the message's acceptance time.
 *
 * <p><b>Steps are scheduled one at a time, not all up front.</b> An earlier
 * version scheduled every step independently against the wall clock (e.g.
 * DISPLAYED always fired at acceptedAt+8s, DELIVERED always at
 * acceptedAt+5s, regardless of each other). Under load, if DELIVERED's
 * queue processing fell behind by more than the 3-second gap between the
 * two, DISPLAYED's task could fire and be dequeued for validation BEFORE
 * DELIVERED had actually been applied - the state machine would then
 * (correctly, given what it could see) reject DISPLAYED as an illegal
 * transition from SUBMITTED, silently dropping that DLR and its webhook.
 * This showed up as a real, reproducible shortfall in DLR counts under
 * concurrent load testing.
 *
 * <p>Instead, {@link #scheduleLifecycle} builds the full plan once (as a
 * {@link PlannedDlrTransition} queue on the message) but only schedules the
 * <em>first</em> step. {@link DlrQueueConsumer} calls {@link #scheduleNext}
 * again only after it has confirmed a step was actually applied - so a
 * later step can never be attempted before the step it depends on has
 * genuinely landed, no matter how far behind the scheduler falls. Each
 * step's target time is still computed from the original acceptedAt-relative
 * delay (so pacing looks the same as before under normal load) - it's used
 * as a lower bound {@link DlrScheduler#scheduleAt} will still fire it
 * immediately if that time has already passed once the plan has caught up.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DlrEngine {

    private final ProviderProperties providerProperties;
    private final DlrScheduler dlrScheduler;
    private final QueueService queueService;
    private final DlrOutcomeStrategy outcomeStrategy;

    public void scheduleLifecycle(MessageContext message) {
        Map<String, Long> delays = providerProperties.getDlr().getDelaysSeconds();
        Instant acceptedAt = message.getAcceptedAt();

        List<PlannedDlrTransition> plan = new ArrayList<>();
        plan.add(plan(MessageState.QUEUED, null, null, acceptedAt, delays));
        plan.add(plan(MessageState.SUBMITTED, null, null, acceptedAt, delays));

        DlrOutcome outcome = outcomeStrategy.decideOutcome(message);

        switch (outcome.getTerminalState()) {
            case FAILED -> plan.add(plan(MessageState.FAILED, outcome.getErrorCode(),
                    outcome.getErrorDescription(), acceptedAt, delays));
            case UNKNOWN -> plan.add(plan(MessageState.UNKNOWN, outcome.getErrorCode(),
                    outcome.getErrorDescription(), acceptedAt, delays));
            case DELIVERED -> {
                plan.add(plan(MessageState.DELIVERED, null, null, acceptedAt, delays));
                if (outcome.isDisplayedAfterDelivered()) {
                    plan.add(plan(MessageState.DISPLAYED, null, null, acceptedAt, delays));
                }
            }
            default -> log.warn("Unhandled DLR outcome {} for message {}",
                    outcome.getTerminalState(), message.getProviderMessageId());
        }

        message.getPendingDlrTransitions().addAll(plan);
        scheduleNext(message);
    }

    /**
     * Schedules the next planned step for this message, if any remain.
     * Called once by {@link #scheduleLifecycle} to kick off the chain, and
     * again by {@link DlrQueueConsumer} each time it confirms a step was
     * actually applied - never speculatively ahead of that confirmation.
     */
    public void scheduleNext(MessageContext message) {
        PlannedDlrTransition next = message.getPendingDlrTransitions().poll();
        if (next == null) {
            return;
        }
        dlrScheduler.scheduleAt(next.fireAt(), () -> {
            DlrTransitionTask task = new DlrTransitionTask(message.getProviderMessageId(),
                    next.state().name(), next.errorCode(), next.errorDescription());
            queueService.publish(QueueNames.DLR, QueueMessage.builder()
                    .correlationId(message.getProviderMessageId())
                    .payload(task)
                    .build());
        });
    }

    /**
     * REJECTED is a single direct transition straight from ACCEPTED (a
     * validation failure, before the message ever reaches the planned
     * lifecycle above) - nothing else depends on it and it depends on
     * nothing else, so it's scheduled directly with no plan/chain needed.
     */
    public void scheduleRejection(MessageContext message, String errorCode, String errorDescription) {
        Map<String, Long> delays = providerProperties.getDlr().getDelaysSeconds();
        PlannedDlrTransition rejection = plan(MessageState.REJECTED, errorCode, errorDescription,
                message.getAcceptedAt(), delays);
        dlrScheduler.scheduleAt(rejection.fireAt(), () -> {
            DlrTransitionTask task = new DlrTransitionTask(message.getProviderMessageId(),
                    rejection.state().name(), rejection.errorCode(), rejection.errorDescription());
            queueService.publish(QueueNames.DLR, QueueMessage.builder()
                    .correlationId(message.getProviderMessageId())
                    .payload(task)
                    .build());
        });
    }

    private PlannedDlrTransition plan(MessageState state, String errorCode, String errorDescription,
                                       Instant acceptedAt, Map<String, Long> delays) {
        long delaySeconds = resolveDelaySeconds(state, delays);
        return new PlannedDlrTransition(state, errorCode, errorDescription, acceptedAt.plusSeconds(delaySeconds));
    }

    /**
     * Normally, each state's delay is whatever operator.dlr.delays-seconds
     * configures for it. Under operator.performance-mode.enabled=true (see
     * ProviderProperties.PerformanceMode), every state instead uses the
     * single operator.performance-mode.dlr-delay-seconds-override value
     * (0 by default) - this is purely a target-time calculation change, not
     * a different code path: the same plan()/scheduleNext() chain still
     * runs, still only schedules one step at a time, still can't let
     * DISPLAYED fire before DELIVERED is confirmed (see class Javadoc) -
     * transitions just target "as soon as possible" instead of a realistic
     * spread, for stress/soak testing this simulator's own ceiling rather
     * than everyday integration testing.
     */
    private long resolveDelaySeconds(MessageState state, Map<String, Long> delays) {
        var performanceMode = providerProperties.getPerformanceMode();
        if (performanceMode.isEnabled()) {
            return Math.max(0, performanceMode.getDlrDelaySecondsOverride());
        }
        return delays == null ? 0L : delays.getOrDefault(state.name(), 0L);
    }
}
