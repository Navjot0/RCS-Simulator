package com.jio.rcs.operator.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.VirtualThreadTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Thread pools backing everything that isn't a pipeline queue dispatcher.
 *
 * <p>There used to be a {@code queueWorkerExecutor} bean here - a single
 * shared, fixed-size platform-thread pool that every pipeline queue's
 * dispatch loops ran on, manually pre-sized to
 * {@code (queue count * workers per queue) + headroom}. That's gone:
 * {@link com.jio.rcs.operator.queue.InMemoryQueueService} now creates and
 * owns one dedicated virtual-thread executor per queue itself (see its
 * class Javadoc for why), so there's no shared pool left to configure or
 * accidentally under-size here.
 *
 * <p>{@code taskScheduler} no longer backs {@link com.jio.rcs.operator.scheduler.DlrScheduler} -
 * that now runs on {@link com.jio.rcs.operator.scheduler.TimingWheelScheduler}
 * instead (see {@code operator.scheduler.*} config and that class's
 * Javadoc for why: a {@link ThreadPoolTaskScheduler}'s single lock-guarded
 * delay queue doesn't scale to the DLR + callback-retry scheduling volume
 * this simulator targets at high TPS). This bean is kept only for the
 * low-frequency {@code @Scheduled} cleanup jobs
 * ({@code MessageStoreCleanupScheduler}, {@code MediaStoreCleanupScheduler}),
 * which have no meaningful throughput requirement and don't need a
 * timing wheel.
 */
@Configuration
@RequiredArgsConstructor
public class AsyncConfig {

    private final ProviderProperties providerProperties;

    /**
     * Small, dedicated executor for {@code @Async} audit logging
     * ({@link com.jio.rcs.operator.audit.AuditLogService}) - deliberately
     * its own bean, never shared with anything on the message-processing
     * hot path. (An earlier version of this class had a bean literally
     * named {@code callbackExecutor} that {@code AuditLogService} was
     * wired to via {@code @Async("callbackExecutor")} - despite the name,
     * it was never actually used for callback delivery, which ran on the
     * old shared queue-worker pool instead. Renamed here to what it's
     * actually for, and callback dispatch now has its own real dedicated
     * executor via InMemoryQueueService.)
     */
    @Bean("auditExecutor")
    public VirtualThreadTaskExecutor auditExecutor() {
        return new VirtualThreadTaskExecutor("audit-");
    }

    @Bean
    public ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        // Sized from operator.queue.scheduler-pool-size - see the Javadoc on
        // ProviderProperties.Queue.schedulerPoolSize for why a fixed pool of
        // 4 became a scaling risk once request volume grew.
        scheduler.setPoolSize(providerProperties.getQueue().getSchedulerPoolSize());
        scheduler.setThreadNamePrefix("scheduler-");
        scheduler.initialize();
        return scheduler;
    }
}
