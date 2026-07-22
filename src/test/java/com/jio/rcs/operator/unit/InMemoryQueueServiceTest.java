package com.jio.rcs.operator.unit;

import com.jio.rcs.operator.config.ProviderProperties;
import com.jio.rcs.operator.metrics.RuntimeMetricsRecorder;
import com.jio.rcs.operator.queue.InMemoryQueueService;
import com.jio.rcs.operator.queue.QueueMessage;
import com.jio.rcs.operator.queue.QueueNames;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves publish() blocks (applies backpressure) rather than silently
 * dropping a message when a queue is at capacity - the fix for the DLR loss
 * reported under concurrent load testing. See InMemoryQueueService's class
 * Javadoc for the full story: an earlier version used offer(), which
 * returns false and logs a WARN instead of enqueuing when the queue is
 * full, which is exactly how DLR events went missing.
 */
class InMemoryQueueServiceTest {

    private InMemoryQueueService newServiceWithDlrCapacity(int capacity) {
        ProviderProperties properties = new ProviderProperties();
        ProviderProperties.Queue queue = new ProviderProperties.Queue();
        queue.setDlrQueueSize(capacity);
        properties.setQueue(queue);

        // InMemoryQueueService now creates its own per-queue virtual-thread
        // executors internally (see its class Javadoc) - there's no executor
        // to inject anymore. This test never calls subscribe() anyway, only
        // publish()/depth()/takeForTest(), so no dispatcher loop ever starts
        // and the metrics recorder (only touched inside dispatchLoop) is never
        // exercised either - a real instance is simplest, no mock needed.
        return new InMemoryQueueService(properties, new RuntimeMetricsRecorder(properties));
    }

    private QueueMessage<String> message(String id) {
        return QueueMessage.<String>builder().messageId(id).payload(id).build();
    }

    @Test
    void publishSucceedsImmediatelyWhileQueueHasSpace() {
        InMemoryQueueService service = newServiceWithDlrCapacity(2);

        service.publish(QueueNames.DLR, message("m1"));
        service.publish(QueueNames.DLR, message("m2"));

        assertThat(service.depth(QueueNames.DLR)).isEqualTo(2);
    }

    @Test
    void publishBlocksInsteadOfDroppingWhenQueueIsFull() throws Exception {
        InMemoryQueueService service = newServiceWithDlrCapacity(1);

        // Fill the only slot.
        service.publish(QueueNames.DLR, message("m1"));

        AtomicBoolean secondPublishReturned = new AtomicBoolean(false);
        CountDownLatch publishStarted = new CountDownLatch(1);

        Thread publisher = new Thread(() -> {
            publishStarted.countDown();
            service.publish(QueueNames.DLR, message("m2")); // must block: queue is full
            secondPublishReturned.set(true);
        });
        publisher.start();
        publishStarted.await();

        // Give the publisher thread every chance to (wrongly) return early if
        // publish() were still using offer()-and-drop instead of put().
        Thread.sleep(300);
        assertThat(secondPublishReturned).isFalse();
        assertThat(service.depth(QueueNames.DLR)).isEqualTo(1);

        // Draining one slot must unblock the pending publish - proving the
        // message was never dropped, just queued behind the blocking call.
        QueueMessage<?> drained = service.takeForTest(QueueNames.DLR);
        assertThat(drained.getMessageId()).isEqualTo("m1");

        publisher.join(2000);
        assertThat(secondPublishReturned).isTrue();
        assertThat(service.depth(QueueNames.DLR)).isEqualTo(1);
    }
}
