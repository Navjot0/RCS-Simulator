package com.jio.rcs.operator.queue;

import com.jio.rcs.operator.config.ProviderProperties;
import com.jio.rcs.operator.metrics.RuntimeMetricsRecorder;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Default in-memory implementation of {@link QueueService}. Each named
 * queue is backed by a bounded {@link LinkedBlockingQueue}; each
 * subscription spins up its configured number of dispatcher loops - one
 * virtual thread each - that block on take() and hand messages to the
 * registered listener. This gives genuine asynchronous, decoupled
 * processing without any external broker, while keeping the same
 * publish/subscribe contract a Kafka or RabbitMQ backed implementation
 * would expose.
 *
 * <p><b>One dedicated executor per queue, not one shared pool for all of
 * them.</b> An earlier version ran every queue's dispatcher loops on a
 * single shared {@code ThreadPoolTaskExecutor}, platform-thread-backed and
 * sized to exactly {@code (queue count * workers per queue) + headroom}.
 * That coupling had two real costs at high TPS: (1) since every dispatcher
 * loop runs forever, the pool's core size had to be manually kept in sync
 * with the total permanent thread count or a queue would silently never get
 * a thread; and (2) because it was one shared pool, a stage that spent a
 * long time per message (e.g. {@code IncomingQueueConsumer}'s simulated
 * latency, or {@code CallbackQueueConsumer} waiting on a slow webhook
 * receiver) tied up platform threads that every other stage was also
 * competing for - so a slow callback receiver could, in the worst case,
 * starve incoming/validation/processing/DLR dispatch of threads too.
 *
 * <p>Each queue now gets its own {@link ExecutorService} created via
 * {@link Executors#newThreadPerTaskExecutor}, backed by Java 21 virtual
 * threads. This fixes both problems at once: a virtual thread that blocks
 * (on {@code Thread.sleep()}, a blocking HTTP call, or {@code queue.take()}
 * itself) unmounts from its carrier instead of occupying a scarce platform
 * thread, so thousands of concurrent in-flight dispatch operations cost
 * only the memory for their (tiny, ~1KB) stacks rather than an OS thread
 * each - and because every queue owns its own executor, one stage running
 * slow can never reduce the thread budget available to another. The
 * configured "worker count" per queue (operator.queue.*-workers, see
 * {@link ProviderProperties.Queue#workersFor(String)}) now means "how many
 * concurrent dispatch loops pull from this queue," not "how many platform
 * threads are reserved for it" - raising it costs almost nothing since
 * virtual threads aren't pooled.
 *
 * <p><b>publish() applies backpressure instead of dropping.</b> An earlier
 * version used {@code offer()} - if a queue was momentarily at capacity,
 * the message was logged and silently discarded, which is exactly how DLR
 * events went missing under high-concurrency load testing (see
 * {@code CHANGES.md}/README "Guaranteed no message loss" section). Every
 * queue in the pipeline (INCOMING -&gt; VALIDATION -&gt; PROCESSING -&gt; DLR -&gt;
 * CALLBACK) is strictly linear with no cycles back to an earlier stage, so
 * blocking a producer when a downstream queue is full only ever slows that
 * one chain down (natural backpressure) - it can never deadlock. The
 * concrete effect: if the CALLBACK queue backs up because your webhook
 * receiver can't keep up, DLR processing slows to match it rather than
 * silently losing events; if INCOMING backs up under a very large burst,
 * {@code POST /v1/messages} itself takes longer to return rather than
 * accepting a message it then can't guarantee will ever be processed.
 * Queue capacities (operator.queue.*-queue-size) are sized generously by
 * default so this only engages under genuinely extreme load - see
 * application.properties.
 */
@Slf4j
@Service
public class InMemoryQueueService implements QueueService {

    private final ProviderProperties providerProperties;
    private final RuntimeMetricsRecorder metricsRecorder;

    private final Map<String, BlockingQueue<QueueMessage<?>>> queues = new ConcurrentHashMap<>();
    private final Map<String, AtomicBoolean> dispatcherStarted = new ConcurrentHashMap<>();
    private final Map<String, ExecutorService> dispatcherExecutors = new ConcurrentHashMap<>();

    public InMemoryQueueService(ProviderProperties providerProperties, RuntimeMetricsRecorder metricsRecorder) {
        this.providerProperties = providerProperties;
        this.metricsRecorder = metricsRecorder;
    }

    private BlockingQueue<QueueMessage<?>> queueFor(String name) {
        return queues.computeIfAbsent(name, n -> new LinkedBlockingQueue<>(resolveCapacity(n)));
    }

    /**
     * One virtual-thread-per-task executor per queue name, created lazily
     * on first subscribe(). Unlike a fixed platform-thread pool, this never
     * needs to be pre-sized against the total number of permanent dispatch
     * loops across every queue - each queue's executor only ever runs that
     * queue's own dispatch loops.
     */
    private ExecutorService executorFor(String queueName) {
        return dispatcherExecutors.computeIfAbsent(queueName, name -> {
            ThreadFactory factory = Thread.ofVirtual().name(name + "-dispatcher-", 0).factory();
            return Executors.newThreadPerTaskExecutor(factory);
        });
    }

    private int resolveCapacity(String name) {
        var q = providerProperties.getQueue();
        return switch (name) {
            case com.jio.rcs.operator.queue.QueueNames.INCOMING -> q.getIncomingQueueSize();
            case com.jio.rcs.operator.queue.QueueNames.VALIDATION -> q.getValidationQueueSize();
            case com.jio.rcs.operator.queue.QueueNames.PROCESSING -> q.getProcessingQueueSize();
            case com.jio.rcs.operator.queue.QueueNames.DLR -> q.getDlrQueueSize();
            case com.jio.rcs.operator.queue.QueueNames.CALLBACK -> q.getCallbackQueueSize();
            default -> q.getCapacity();
        };
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> void publish(String queueName, QueueMessage<T> message) {
        BlockingQueue<QueueMessage<?>> queue = queueFor(queueName);
        try {
            // put() blocks until space is available instead of offer()'s drop-on-full -
            // see class Javadoc. This is the guarantee that no message/DLR is ever lost
            // to a momentarily-full queue, no matter the volume.
            queue.put(message);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted while publishing message {} to queue '{}' - message was NOT enqueued " +
                    "(this only happens on shutdown/thread interruption, never from queue capacity)",
                    message.getMessageId(), queueName, e);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> boolean tryPublish(String queueName, QueueMessage<T> message, long timeoutMillis) {
        BlockingQueue<QueueMessage<?>> queue = queueFor(queueName);
        try {
            // offer(timeout) instead of put() - see QueueService.tryPublish's
            // Javadoc for why this is deliberately different from publish()
            // above: this is the one place a bounded wait (and an explicit
            // "no" past that bound) is preferable to the zero-loss
            // guarantee's unbounded blocking, because the caller is a live
            // HTTP request whose client has its own timeout waiting on it.
            return queue.offer(message, timeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted while publishing message {} to queue '{}' - message was NOT enqueued",
                    message.getMessageId(), queueName, e);
            return false;
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> void subscribe(String queueName, QueueListener<T> listener) {
        BlockingQueue<QueueMessage<?>> queue = queueFor(queueName);
        AtomicBoolean started = dispatcherStarted.computeIfAbsent(queueName, n -> new AtomicBoolean(false));
        if (started.compareAndSet(false, true)) {
            int workers = providerProperties.getQueue().workersFor(queueName);
            ExecutorService executor = executorFor(queueName);
            for (int i = 0; i < workers; i++) {
                executor.submit(() -> dispatchLoop(queueName, queue, listener));
            }
            log.info("Started {} virtual-thread dispatcher(s) for queue '{}' on its own dedicated executor",
                    workers, queueName);
        }
    }

    @SuppressWarnings("unchecked")
    private void dispatchLoop(String queueName, BlockingQueue<QueueMessage<?>> queue, QueueListener<?> listener) {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                QueueMessage<?> message = queue.take();
                // Queue-wait and per-stage latency/utilization recording below is
                // the whole basis for GET /metrics' runtime.queueWaitMillis /
                // stageLatencyMillis / workerUtilizationPercent (see
                // RuntimeMetricsRecorder) - instrumenting it once here, generically
                // for every queue, covers all five pipeline stages (including
                // CALLBACK, where stage latency is effectively first-attempt
                // callback-delivery latency) without touching each consumer
                // individually. Every call below is a no-op single boolean check
                // when operator.metrics.enabled=false.
                metricsRecorder.recordQueueWait(queueName, Duration.between(message.getEnqueuedAt(), Instant.now()).toMillis());
                metricsRecorder.workerStarted(queueName);
                long stageStartNanos = System.nanoTime();
                try {
                    ((QueueListener<Object>) listener).onMessage((QueueMessage<Object>) message);
                } catch (InterruptedException ie) {
                    // A listener can block on something interruptible (e.g. the
                    // IncomingQueueConsumer's latency-simulation Thread.sleep()).
                    // Catching InterruptedException clears the thread's interrupt
                    // flag as a side effect of the JVM throwing it, so if this
                    // were swallowed by the generic Exception handler below the
                    // loop would keep running right through a shutdown request -
                    // which is exactly what caused the executor to hang for 30s
                    // on shutdown instead of exiting cleanly. Restore the flag
                    // and stop this dispatcher immediately instead.
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.error("Listener failed processing message {} from queue '{}': {}",
                            message.getMessageId(), queueName, e.getMessage(), e);
                } finally {
                    // Runs on every exit path (normal return, the caught Exception
                    // above, and the InterruptedException break above too - finally
                    // always runs before a break unwinds) so the busy-worker count
                    // driving workerUtilizationPercent can never leak upward.
                    metricsRecorder.recordStageLatency(queueName, Duration.ofNanos(System.nanoTime() - stageStartNanos).toMillis());
                    metricsRecorder.workerFinished(queueName);
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        log.debug("Dispatcher thread for queue '{}' shutting down", queueName);
    }

    @Override
    public int depth(String queueName) {
        return queueFor(queueName).size();
    }

    /**
     * Test-only escape hatch: drains one message directly, bypassing
     * subscribe()'s dispatcher loop. Package-private production code has no
     * use for this - only InMemoryQueueServiceTest, to prove publish()
     * genuinely blocks on a full queue and unblocks once space frees up,
     * without needing a real listener/executor wired up.
     */
    public QueueMessage<?> takeForTest(String queueName) throws InterruptedException {
        return queueFor(queueName).take();
    }

    @Override
    public Map<String, Integer> depths() {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (String name : QueueNames.ALL) {
            result.put(name, queueFor(name).size());
        }
        return result;
    }

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down in-memory queue service - stopping {} per-queue dispatcher executor(s)",
                dispatcherExecutors.size());
        // Each queue's dispatch loops check Thread.currentThread().isInterrupted()
        // between take() calls (see dispatchLoop), so shutdownNow()'s interrupt
        // signal is enough for a prompt, clean exit - no separate awaitTermination
        // dance needed per executor since there's nothing stateful to flush.
        dispatcherExecutors.values().forEach(ExecutorService::shutdownNow);
    }
}
