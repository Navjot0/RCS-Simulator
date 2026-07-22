package com.jio.rcs.operator.processor;

import com.jio.rcs.operator.model.MessageContext;
import com.jio.rcs.operator.queue.QueueMessage;
import com.jio.rcs.operator.queue.QueueNames;
import com.jio.rcs.operator.queue.QueueService;
import com.jio.rcs.operator.registry.MessageStore;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Second pipeline stage: runs agent-authorization business validation.
 * Rejected messages skip straight to the DLR engine's rejection path;
 * accepted messages proceed to the Processing queue.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ValidationQueueConsumer {

    private final QueueService queueService;
    private final MessageStore messageStore;
    private final ValidationProcessor validationProcessor;
    private final DlrEngine dlrEngine;

    @PostConstruct
    public void subscribe() {
        queueService.<ValidationTask>subscribe(QueueNames.VALIDATION, message -> handle(message.getPayload()));
    }

    private void handle(ValidationTask task) {
        MessageContext message = messageStore.find(task.providerMessageId()).orElse(null);
        if (message == null) {
            log.warn("Validation stage: message {} not found", task.providerMessageId());
            return;
        }

        var result = validationProcessor.validate(message);
        if (!result.isValid()) {
            // DEBUG, not INFO - see MessageProcessor for why per-message
            // logging defaults to DEBUG in this simulator.
            log.debug("Message {} rejected at validation: {}", message.getProviderMessageId(), result.getErrorCode());
            dlrEngine.scheduleRejection(message, result.getErrorCode(), result.getErrorDescription());
            return;
        }

        queueService.publish(QueueNames.PROCESSING, QueueMessage.builder()
                .correlationId(task.providerMessageId())
                .payload(new ProcessingTask(task.providerMessageId()))
                .build());
    }
}
