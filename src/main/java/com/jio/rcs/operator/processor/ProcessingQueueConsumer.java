package com.jio.rcs.operator.processor;

import com.jio.rcs.operator.model.MessageContext;
import com.jio.rcs.operator.queue.QueueNames;
import com.jio.rcs.operator.queue.QueueService;
import com.jio.rcs.operator.registry.MessageStore;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Third pipeline stage: hands validated messages to the DLR engine, which
 * schedules the remaining lifecycle transitions at their configured delays.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProcessingQueueConsumer {

    private final QueueService queueService;
    private final MessageStore messageStore;
    private final DlrEngine dlrEngine;

    @PostConstruct
    public void subscribe() {
        queueService.<ProcessingTask>subscribe(QueueNames.PROCESSING, message -> handle(message.getPayload()));
    }

    private void handle(ProcessingTask task) {
        MessageContext message = messageStore.find(task.providerMessageId()).orElse(null);
        if (message == null) {
            log.warn("Processing stage: message {} not found", task.providerMessageId());
            return;
        }
        dlrEngine.scheduleLifecycle(message);
    }
}
