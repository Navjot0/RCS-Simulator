package com.jio.rcs.operator.queue;

@FunctionalInterface
public interface QueueListener<T> {
    void onMessage(QueueMessage<T> message) throws Exception;
}
