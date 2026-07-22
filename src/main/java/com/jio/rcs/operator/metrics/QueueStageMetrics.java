package com.jio.rcs.operator.metrics;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Everything needed to decide whether one pipeline stage's worker count
 * (operator.queue.*-workers) needs tuning, in one place - see
 * {@link RuntimeMetrics#getPerStage()}. Populated for all five pipeline
 * queues (see {@link com.jio.rcs.operator.queue.QueueNames#ALL}) on every
 * GET /metrics call, even before any traffic has flowed (configuredWorkers
 * is always known; everything else simply reads 0 until a message passes
 * through that stage).
 *
 * <p>Reading these together is the intended workflow during load testing:
 * {@code queueDepth} climbing while {@code idleWorkers} stays near 0 means
 * that stage is the bottleneck and its worker count should go up;
 * {@code idleWorkers} staying high while {@code queueDepth} is also low
 * means that stage has more concurrency configured than it currently needs.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class QueueStageMetrics {

    private String queueName;

    /** operator.queue.*-workers (or the worker-threads/4 fallback) - how many dispatcher loops this stage was started with. */
    private int configuredWorkers;

    /** How many of those dispatcher loops are busy processing a message right now. */
    private int activeWorkers;

    /** configuredWorkers - activeWorkers (floored at 0) - how many dispatcher loops are currently waiting on an empty queue. */
    private int idleWorkers;

    /** Current number of messages sitting in this queue, waiting to be dequeued - from QueueService.depths(). */
    private int queueDepth;

    /** Lifetime count of messages this stage has finished processing (successfully or not) since the process started. */
    private long messagesProcessed;

    /** Average wall-clock time this stage's listener took to process one message, once dequeued. */
    private double averageProcessingTimeMillis;

    /** Average time a message sat in this queue before a dispatcher loop picked it up. */
    private double averageQueueWaitMillis;
}
