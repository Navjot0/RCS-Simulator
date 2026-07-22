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

    <T> void subscribe(String queueName, QueueListener<T> listener);

    int depth(String queueName);

    Map<String, Integer> depths();
}
