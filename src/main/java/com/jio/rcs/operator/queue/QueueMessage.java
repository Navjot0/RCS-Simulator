package com.jio.rcs.operator.queue;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Generic envelope travelling through the internal queue pipeline. Mirrors
 * the kind of envelope a Kafka ConsumerRecord or RabbitMQ Message would
 * provide (id, correlation id, timestamp, payload) so business logic never
 * depends on the transport.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class QueueMessage<T> {

    @Builder.Default
    private String messageId = UUID.randomUUID().toString();

    private String correlationId;

    private T payload;

    @Builder.Default
    private Instant enqueuedAt = Instant.now();

    @Builder.Default
    private int attempt = 1;
}
