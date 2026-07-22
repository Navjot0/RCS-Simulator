package com.jio.rcs.operator.unit;

import com.jio.rcs.operator.config.ProviderProperties;
import com.jio.rcs.operator.model.MessageContext;
import com.jio.rcs.operator.processor.DlrEngine;
import com.jio.rcs.operator.processor.DlrOutcome;
import com.jio.rcs.operator.processor.DlrOutcomeStrategy;
import com.jio.rcs.operator.queue.QueueService;
import com.jio.rcs.operator.scheduler.DlrScheduler;
import com.jio.rcs.operator.statemachine.MessageState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Proves DlrEngine schedules a message's lifecycle one step at a time
 * rather than all up front. This matters because independently scheduling
 * every step against the wall clock (the original design) let a later step
 * fire before an earlier one it causally depends on had actually been
 * applied under load - e.g. DISPLAYED (acceptedAt+8s) racing ahead of
 * DELIVERED (acceptedAt+5s) if DELIVERED's own processing fell more than 3
 * seconds behind schedule - which the state machine would then legitimately
 * reject as illegal, silently dropping that DLR and its webhook. See
 * DlrEngine's class Javadoc.
 */
class DlrEngineTest {

    private DlrScheduler dlrScheduler;
    private QueueService queueService;
    private DlrOutcomeStrategy outcomeStrategy;
    private DlrEngine dlrEngine;

    @BeforeEach
    void setUp() {
        ProviderProperties providerProperties = new ProviderProperties();
        ProviderProperties.Dlr dlr = new ProviderProperties.Dlr();
        dlr.setDelaysSeconds(Map.of(
                "QUEUED", 1L, "SUBMITTED", 2L, "DELIVERED", 5L, "DISPLAYED", 8L));
        providerProperties.setDlr(dlr);

        dlrScheduler = mock(DlrScheduler.class);
        queueService = mock(QueueService.class);
        outcomeStrategy = mock(DlrOutcomeStrategy.class);

        dlrEngine = new DlrEngine(providerProperties, dlrScheduler, queueService, outcomeStrategy);
    }

    private MessageContext sampleMessage() {
        return MessageContext.builder()
                .providerMessageId("SIMTEST0003")
                .status(MessageState.ACCEPTED.name())
                .acceptedAt(Instant.now())
                .build();
    }

    @Test
    void scheduleLifecycleOnlySchedulesTheFirstStepUpFront() {
        when(outcomeStrategy.decideOutcome(any()))
                .thenReturn(new DlrOutcome(MessageState.DELIVERED, true, null, null));

        MessageContext message = sampleMessage();
        dlrEngine.scheduleLifecycle(message);

        // Full plan is QUEUED, SUBMITTED, DELIVERED, DISPLAYED (4 steps) - only
        // the first should be scheduled immediately; the rest wait in the queue.
        verify(dlrScheduler, times(1)).scheduleAt(any(), any());
        assertThat(message.getPendingDlrTransitions()).hasSize(3);
    }

    @Test
    void scheduleNextAdvancesOneStepAtATimeThroughTheFullPlan() {
        when(outcomeStrategy.decideOutcome(any()))
                .thenReturn(new DlrOutcome(MessageState.DELIVERED, true, null, null));

        MessageContext message = sampleMessage();
        dlrEngine.scheduleLifecycle(message); // schedules QUEUED (1st call), 3 remain

        dlrEngine.scheduleNext(message); // schedules SUBMITTED (2nd call), 2 remain
        assertThat(message.getPendingDlrTransitions()).hasSize(2);

        dlrEngine.scheduleNext(message); // schedules DELIVERED (3rd call), 1 remains
        assertThat(message.getPendingDlrTransitions()).hasSize(1);

        dlrEngine.scheduleNext(message); // schedules DISPLAYED (4th call), 0 remain
        assertThat(message.getPendingDlrTransitions()).isEmpty();

        verify(dlrScheduler, times(4)).scheduleAt(any(), any());

        // Plan exhausted - calling again must not schedule a 5th, phantom step.
        dlrEngine.scheduleNext(message);
        verify(dlrScheduler, times(4)).scheduleAt(any(), any());
    }

    @Test
    void failedOutcomeProducesAThreeStepPlanWithNoDisplayedStep() {
        when(outcomeStrategy.decideOutcome(any()))
                .thenReturn(new DlrOutcome(MessageState.FAILED, false, "DEVICE_OFFLINE", "Destination device is currently offline"));

        MessageContext message = sampleMessage();
        dlrEngine.scheduleLifecycle(message);

        // QUEUED scheduled immediately; SUBMITTED and FAILED remain queued - no DISPLAYED step at all.
        assertThat(message.getPendingDlrTransitions())
                .extracting(t -> t.state())
                .containsExactly(MessageState.SUBMITTED, MessageState.FAILED);
    }

    @Test
    void deliveredWithoutDisplayProducesNoDisplayedStep() {
        when(outcomeStrategy.decideOutcome(any()))
                .thenReturn(new DlrOutcome(MessageState.DELIVERED, false, null, null));

        MessageContext message = sampleMessage();
        dlrEngine.scheduleLifecycle(message);

        assertThat(message.getPendingDlrTransitions())
                .extracting(t -> t.state())
                .containsExactly(MessageState.SUBMITTED, MessageState.DELIVERED);
    }
}
