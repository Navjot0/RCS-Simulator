package com.jio.rcs.operator.queue;

import java.util.List;

/**
 * Logical queue/topic names for the internal pipeline:
 * Incoming -> Validation -> Processing -> DLR -> Callback.
 * Kept as plain string constants so a future Kafka/RabbitMQ implementation
 * of {@link QueueService} can map them 1:1 onto real topics/exchanges.
 */
public final class QueueNames {
    public static final String INCOMING = "incoming-queue";
    public static final String VALIDATION = "validation-queue";
    public static final String PROCESSING = "processing-queue";
    public static final String DLR = "dlr-queue";
    public static final String CALLBACK = "callback-queue";

    /**
     * All pipeline queues. Each one gets its own dedicated virtual-thread
     * executor and permanently-running dispatcher loop(s) in
     * InMemoryQueueService (one executor per queue name, not a shared pool -
     * see that class's Javadoc) - use this constant rather than a hardcoded
     * queue count so iteration (e.g. QueueService.depths()) stays in sync if
     * a queue is ever added or removed.
     */
    public static final List<String> ALL = List.of(INCOMING, VALIDATION, PROCESSING, DLR, CALLBACK);

    private QueueNames() {
    }
}
