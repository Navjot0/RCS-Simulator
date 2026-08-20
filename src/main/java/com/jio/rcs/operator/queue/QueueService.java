package com.jio.rcs.operator.queue;

import java.util.Map;

/**
 * Abstraction over the queue transport used for the internal pipeline
 * (incoming -> validation -> processing -> DLR -> callback).
 *
 * The default implementation ({@link InMemoryQueueService}) is a pure
 * in-JVM BlockingQueue-backed engine, but any consumer of this interface
 * (processors, DLR engine, callback engine) is transport-agnostic. Swapping
 * to Kafka or RabbitMQ later only requires providing a new implementation
 * of this interface - no business logic changes.
 */
public interface QueueService {

    <T> void publish(String queueName, QueueMessage<T> message);

    /**
     * Same as {@link #publish}, except it gives up and returns {@code false}
     * instead of blocking indefinitely if the queue doesn't have space
     * within {@code timeoutMillis}. Intended specifically for the
     * client-facing admission path (INCOMING), where an unbounded wait
     * turns into unbounded client-visible response time - internal
     * stage-to-stage handoffs (VALIDATION/PROCESSING/DLR/CALLBACK) should
     * keep using the plain blocking {@link #publish} so the zero-DLR-loss
     * guarantee for already-accepted messages is untouched.
     *
     * @return true if the message was enqueued within the timeout, false if it timed out first (nothing was enqueued).
     */
    <T> boolean tryPublish(String queueName, QueueMessage<T> message, long timeoutMillis);

    <T> void subscribe(String queueName, QueueListener<T> listener);

    int depth(String queueName);

    Map<String, Integer> depths();
}
