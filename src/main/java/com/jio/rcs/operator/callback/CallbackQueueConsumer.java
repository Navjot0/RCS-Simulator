package com.jio.rcs.operator.callback;

import com.jio.rcs.operator.model.MessageContext;
import com.jio.rcs.operator.queue.QueueNames;
import com.jio.rcs.operator.queue.QueueService;
import com.jio.rcs.operator.registry.MessageStore;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Fifth and final pipeline stage: fires the CPaaS webhook whenever a
 * message's status has changed.
 *
 * <p><b>Isolated from every other stage by construction.</b> This consumer's
 * dispatch loops run on the CALLBACK queue's own dedicated virtual-thread
 * executor (see {@link com.jio.rcs.operator.queue.InMemoryQueueService}) -
 * a completely separate {@link java.util.concurrent.ExecutorService} from
 * INCOMING/VALIDATION/PROCESSING/DLR. {@link CallbackEngine#deliver} makes a
 * blocking HTTP call per attempt (see {@link CallbackClient}); if a webhook
 * receiver is slow or unreachable, every CALLBACK dispatch loop can be tied
 * up waiting on it without taking a single thread away from DLR generation
 * or any earlier stage - DLR processing keeps running at full speed
 * regardless of how far behind callback delivery falls. Raise
 * {@code operator.queue.callback-workers} if you need more concurrent
 * in-flight webhook calls than the default.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CallbackQueueConsumer {

    private final QueueService queueService;
    private final MessageStore messageStore;
    private final CallbackEngine callbackEngine;

    @PostConstruct
    public void subscribe() {
        queueService.<CallbackTask>subscribe(QueueNames.CALLBACK, message -> handle(message.getPayload()));
    }

    private void handle(CallbackTask task) {
        MessageContext message = messageStore.find(task.providerMessageId()).orElse(null);
        if (message == null) {
            log.warn("Callback stage: message {} not found", task.providerMessageId());
            return;
        }
        callbackEngine.deliver(message);
    }
}
