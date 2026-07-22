package com.jio.rcs.operator.processor;

import com.jio.rcs.operator.callback.CallbackTask;
import com.jio.rcs.operator.model.MessageContext;
import com.jio.rcs.operator.queue.QueueMessage;
import com.jio.rcs.operator.queue.QueueNames;
import com.jio.rcs.operator.queue.QueueService;
import com.jio.rcs.operator.registry.MessageStore;
import com.jio.rcs.operator.statemachine.MessageState;
import com.jio.rcs.operator.statemachine.StateMachineService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Fourth pipeline stage: applies a scheduled DLR transition to the message
 * (validated against the configurable state machine), records it in the
 * message's in-memory history, then hands off to the Callback queue so the
 * CPaaS webhook gets notified - this is the actual "DLR generation" moment.
 *
 * <p>The DLR queue runs with more than one dispatcher thread (see
 * {@code operator.queue.dlr-workers} / {@code workersFor()} in
 * {@link com.jio.rcs.operator.config.ProviderProperties.Queue}), and a
 * single message's several transitions (QUEUED, SUBMITTED, DELIVERED, ...)
 * can end up dequeued and processed by two different threads close together
 * in time - especially under burst load, where the scheduler firing them
 * slightly late compresses their effective timing. The read-current-state
 * -&gt; validate -&gt; apply sequence below is therefore synchronized per
 * message: without it, two threads can both read the same "from" state
 * before either commits its write, and the state machine then legitimately
 * (but wrongly, from the caller's perspective) rejects one of the two
 * transitions as illegal - silently dropping that DLR event and the webhook
 * callback that would have carried it, with only a WARN log to show for it.
 *
 * <p><b>The CALLBACK-queue publish happens outside the synchronized block.</b>
 * Publishing can block (see InMemoryQueueService's backpressure-instead-of-
 * drop guarantee) if the CALLBACK queue is momentarily full - there's no
 * correctness reason that blocking wait needs to happen while still holding
 * this message's monitor, so it doesn't: the lock only guards the part that
 * actually needs atomicity (read state, validate, apply, plan the next
 * step), and is released before the potentially-slow publish call.
 *
 * <p>This alone isn't sufficient, though: {@link DlrEngine} no longer
 * schedules a message's whole lifecycle up front against the wall clock -
 * it schedules one step, and this consumer schedules the next one (via
 * {@link DlrEngine#scheduleNext}) only after confirming the current one was
 * actually applied, inside the same {@code synchronized(message)} block
 * below. That's what guarantees, for example, DISPLAYED can never even be
 * attempted until DELIVERED has genuinely landed, regardless of how far
 * behind the scheduler falls under load.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DlrQueueConsumer {

    private final QueueService queueService;
    private final MessageStore messageStore;
    private final StateMachineService stateMachineService;
    private final DlrEngine dlrEngine;

    @PostConstruct
    public void subscribe() {
        queueService.<DlrTransitionTask>subscribe(QueueNames.DLR, message -> handle(message.getPayload()));
    }

    public void handle(DlrTransitionTask task) {
        MessageContext message = messageStore.find(task.providerMessageId()).orElse(null);
        if (message == null) {
            log.warn("DLR stage: message {} not found", task.providerMessageId());
            return;
        }

        // Synchronized on the message itself so the read-check-apply sequence is
        // atomic per message, even though two different transitions for the same
        // message can be dequeued by two different DLR dispatcher threads at
        // nearly the same time - see class Javadoc for why this matters. Kept as
        // narrow as possible: only the state read/validate/apply/plan-next-step
        // sequence runs inside it - the potentially-blocking CALLBACK publish
        // happens after the lock is released (see class Javadoc).
        boolean transitioned;
        synchronized (message) {
            MessageState from = MessageState.valueOf(message.getStatus());
            MessageState to = MessageState.valueOf(task.newState());

            if (!stateMachineService.canTransition(from, to)) {
                log.warn("Skipping illegal DLR transition {} -> {} for message {}", from, to, task.providerMessageId());
                return;
            }

            message.applyTransition(to.name(), task.errorCode(), task.errorDescription());
            // DEBUG, not INFO: fires per DLR transition (several per message) -
            // see MessageProcessor for why per-message/-event logging defaults
            // to DEBUG rather than INFO in this simulator.
            log.debug("DLR: message {} transitioned {} -> {}", message.getProviderMessageId(), from, to);
            transitioned = true;

            // Only now that this step is confirmed applied is it safe to schedule
            // whatever comes next in the plan (e.g. DISPLAYED after DELIVERED) -
            // see class Javadoc. This must stay inside the lock (unlike the
            // publish below) because it reads/mutates the message's pending-
            // transitions queue, which is part of the same per-message state
            // this lock protects.
            dlrEngine.scheduleNext(message);
        }

        if (transitioned) {
            queueService.publish(QueueNames.CALLBACK, QueueMessage.builder()
                    .correlationId(message.getProviderMessageId())
                    .payload(new CallbackTask(message.getProviderMessageId()))
                    .build());
        }
    }
}
