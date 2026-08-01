package com.jio.rcs.operator.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Root binding for all "operator.*" configuration.
 *
 * <p>This is a generic, open RCS provider simulator: it accepts a message
 * from any caller (no authentication, no registered-client/agent concept)
 * and applies exactly one behaviour bundle - identity, TPS ceiling, latency,
 * DLR delays, delivery probability, error taxonomy - to every request. There
 * used to be a per-client, multi-provider-profile system here
 * (operator.clients[N] + operator.profiles.&lt;NAME&gt;, each client
 * authenticated and auto-routed to its own simulated operator); that's gone.
 * If you need to simulate several distinct operators again, the shape to
 * reintroduce is a {@code Map<String, Behavior>} resolved by something in
 * the request rather than by an authenticated identity.
 */
@Data
@Component
@Validated
@ConfigurationProperties(prefix = "operator")
public class ProviderProperties {

    /**
     * Ceiling applied to every worker-count-style property below
     * (per-stage queue workers, the legacy worker-threads fallback base,
     * the scheduler pool sizes). Not a hard technical limit - Java 21
     * virtual threads could nominally support far more - but a value in
     * this neighbourhood or beyond is almost certainly a typo or a
     * misunderstanding of what the setting controls (a dispatcher-loop
     * *count*, not a queue capacity), so it's rejected at startup with a
     * clear message rather than silently creating tens of thousands of
     * permanently-running dispatch loops for a single pipeline stage.
     */
    static final int MAX_REASONABLE_WORKERS = 4096;

    @Valid
    private Queue queue = new Queue();
    private StateMachine stateMachine = new StateMachine();
    private Callback callback = new Callback();
    private Bulk bulk = new Bulk();
    private MessageStore messageStore = new MessageStore();
    private Media media = new Media();
    private Capability capability = new Capability();

    /** Display identity for this simulator, echoed into generated ids and the DLR webhook's agent.provider field. */
    private Identity identity = new Identity();

    private Tps tps = new Tps();
    private Latency latency = new Latency();
    private Dlr dlr = new Dlr();
    private Probability probability = new Probability();
    private ErrorSimulation errorSimulation = new ErrorSimulation();

    /** Timing-wheel scheduler backing DLR transitions and callback retry backoff - see TimingWheelScheduler. */
    @Valid
    private Scheduler scheduler = new Scheduler();

    /** Stress-testing mode that collapses timing to (near) zero - see PerformanceMode class Javadoc. */
    private PerformanceMode performanceMode = new PerformanceMode();

    /** Toggle for the runtime metrics recorder - see RuntimeMetricsRecorder. */
    private Metrics metrics = new Metrics();

    /**
     * Where every DLR/dispatch webhook is sent by default. A request can
     * still override this per-message via {@code SendMessageRequest.callbackUrl}
     * - this is just what's used when a request doesn't set one.
     */
    private String callbackUrl = "http://localhost:9000/webhook";

    /**
     * Multi-instance DLR callback routing: {@code operator.instances.<name>.profiles.<provider>.callback-url}.
     * Lets one simulator deployment serve several CPaaS instances (dev/staging/cerf/...)
     * that each need their own callback destination per real-provider wire
     * profile, selected via the {@code /{instance}/wire/{provider}/...} URL
     * prefix (see {@link com.jio.rcs.operator.wire.CallbackUrlResolver}).
     * Deliberately a plain {@code Map} - adding a new instance is a config-only
     * change, no Java code touches instance names. Kept separate from
     * {@link WireProviderProperties} (which still owns each profile's
     * enabled/disabled state and its single legacy default callback-url for
     * the un-prefixed {@code /wire/{provider}/...} routes) since this is a
     * distinct, orthogonal concern: routing the same profile's DLR to a
     * different destination per calling CPaaS instance, not per-profile
     * defaults.
     */
    private Map<String, Instance> instances = new LinkedHashMap<>();

    @Data
    public static class Instance {
        private Map<String, InstanceProfile> profiles = new LinkedHashMap<>();
    }

    @Data
    public static class InstanceProfile {
        /** Full, complete callback URL - never built from a base-url + path suffix, since environments don't share a common host or path shape. */
        private String callbackUrl;
    }

    @Data
    public static class Identity {
        private String providerName = "RCS_SIMULATOR";
        private String providerCode = "SIM";

        /** Human-readable name used in the DLR webhook's {@code agent.provider} field. */
        private String providerDisplayName = "RCS Provider Simulator";

        /**
         * Simulated bot/agent registration id echoed into every DLR webhook's
         * {@code delivery_info.delivery_status.webhook_data.botId} /
         * {@code additional_data.webhook_data.botId} - purely cosmetic, for
         * parity with a real captured provider payload shape. Configurable,
         * no other component reads this value.
         */
        private String botId = "SIMULATOR-BOT-000000000001";
    }

    @Data
    public static class Tps {
        private boolean enabled = true;
        private int limit = 1000;
        private long windowMillis = 1000;
    }

    /**
     * Configures {@link com.jio.rcs.operator.scheduler.TimingWheelScheduler} -
     * the hierarchical timing wheel backing every DLR lifecycle transition
     * and callback retry backoff. Replaces the old single
     * {@code ScheduledThreadPoolExecutor}-backed scheduler (still present
     * as the {@code taskScheduler} bean in AsyncConfig, but now only used
     * for the low-frequency MessageStore/MediaStore cleanup jobs, not the
     * high-volume DLR/callback scheduling path). See TimingWheelScheduler's
     * class Javadoc for the full "why a timing wheel" rationale.
     */
    @Data
    public static class Scheduler {
        /** Wheel tick width in milliseconds - bounds scheduling resolution (a task may fire up to one tick late). */
        @Min(value = 1, message = "operator.scheduler.tick-duration-millis must be at least 1")
        private long tickDurationMillis = 100;
        /** Number of buckets in the wheel; one full revolution spans wheelSize * tickDurationMillis (default ~51.2s). */
        @Min(value = 2, message = "operator.scheduler.wheel-size must be at least 2")
        private int wheelSize = 512;
        /** Cap on how many due tasks may run concurrently at once (see TimingWheelScheduler - virtual threads have no fixed pool size to bound instead). */
        @Min(value = 1, message = "operator.scheduler.worker-count must be at least 1")
        @Max(value = MAX_REASONABLE_WORKERS, message = "operator.scheduler.worker-count must not exceed " + MAX_REASONABLE_WORKERS + " - this is a dispatcher-loop count, not a queue capacity")
        private int workerCount = 16;
    }

    /**
     * Stress-testing mode: when enabled, collapses the simulated ingestion
     * latency (operator.latency.*) and per-state DLR delays
     * (operator.dlr.delays-seconds.*) toward (near) zero, without touching
     * anything else - the state machine, DLR outcome probabilities,
     * response DTOs, and webhook payload shapes are all the exact same code
     * path as normal operation, just reached faster. Intended purely for
     * load/soak testing this simulator's own throughput ceiling, not for
     * everyday integration testing (where realistic timing is usually the
     * point).
     */
    @Data
    public static class PerformanceMode {
        private boolean enabled = false;
        /** Simulated ingestion latency floor/ceiling used instead of operator.latency.* while enabled - both 0 by default (no artificial delay at all). */
        private long latencyMinMillis = 0;
        private long latencyMaxMillis = 0;
        /** Per-state DLR delay (seconds) used instead of operator.dlr.delays-seconds.* while enabled - 0 by default (every transition fires as fast as the scheduler tick allows). */
        private long dlrDelaySecondsOverride = 0;
    }

    /** Toggles the runtime metrics recorder - see RuntimeMetricsRecorder class Javadoc. */
    @Data
    public static class Metrics {
        private boolean enabled = true;
    }

    @Data
    public static class Queue {
        private int capacity = 100000;

        /**
         * Legacy/fallback dispatcher-thread count, still used to derive a
         * default via {@link #dispatcherWorkersPerQueue()} for any pipeline
         * stage that doesn't set its own {@code operator.queue.*-workers}
         * override below. Kept unrenamed for backward compatibility -
         * existing deployments that only set this one property keep working
         * identically.
         */
        @Min(value = 1, message = "operator.queue.worker-threads must be at least 1")
        @Max(value = MAX_REASONABLE_WORKERS, message = "operator.queue.worker-threads must not exceed " + MAX_REASONABLE_WORKERS)
        private int workerThreads = 32;

        private int incomingQueueSize = 50000;
        private int validationQueueSize = 50000;
        private int processingQueueSize = 50000;
        private int dlrQueueSize = 50000;
        private int callbackQueueSize = 50000;

        /**
         * Per-stage dispatcher-loop counts (operator.queue.incoming-workers,
         * validation-workers, processing-workers, dlr-workers,
         * callback-workers). Each is nullable/unset by default, meaning
         * "fall back to {@link #dispatcherWorkersPerQueue()}" - set any
         * subset of these to tune one stage's concurrency independently of
         * the others (e.g. give CALLBACK far more concurrent dispatch loops
         * than DLR if your webhook receiver is slow, without touching
         * anything else). Since InMemoryQueueService now runs each queue's
         * dispatch loops on their own dedicated virtual-thread executor
         * (see its class Javadoc), raising one stage's worker count no
         * longer takes threads away from any other stage - unlike the old
         * shared fixed-size platform-thread pool, where every stage drew
         * from the same limited bucket.
         */
        @Min(value = 1, message = "operator.queue.incoming-workers must be at least 1 (omit the property entirely to use the worker-threads/4 fallback instead of 0)")
        @Max(value = MAX_REASONABLE_WORKERS, message = "operator.queue.incoming-workers must not exceed " + MAX_REASONABLE_WORKERS + " - this is a dispatcher-loop count, not a queue capacity")
        private Integer incomingWorkers;

        @Min(value = 1, message = "operator.queue.validation-workers must be at least 1 (omit the property entirely to use the worker-threads/4 fallback instead of 0)")
        @Max(value = MAX_REASONABLE_WORKERS, message = "operator.queue.validation-workers must not exceed " + MAX_REASONABLE_WORKERS + " - this is a dispatcher-loop count, not a queue capacity")
        private Integer validationWorkers;

        @Min(value = 1, message = "operator.queue.processing-workers must be at least 1 (omit the property entirely to use the worker-threads/4 fallback instead of 0)")
        @Max(value = MAX_REASONABLE_WORKERS, message = "operator.queue.processing-workers must not exceed " + MAX_REASONABLE_WORKERS + " - this is a dispatcher-loop count, not a queue capacity")
        private Integer processingWorkers;

        @Min(value = 1, message = "operator.queue.dlr-workers must be at least 1 (omit the property entirely to use the worker-threads/4 fallback instead of 0)")
        @Max(value = MAX_REASONABLE_WORKERS, message = "operator.queue.dlr-workers must not exceed " + MAX_REASONABLE_WORKERS + " - this is a dispatcher-loop count, not a queue capacity")
        private Integer dlrWorkers;

        @Min(value = 1, message = "operator.queue.callback-workers must be at least 1 (omit the property entirely to use the worker-threads/4 fallback instead of 0)")
        @Max(value = MAX_REASONABLE_WORKERS, message = "operator.queue.callback-workers must not exceed " + MAX_REASONABLE_WORKERS + " - this is a dispatcher-loop count, not a queue capacity")
        private Integer callbackWorkers;

        /**
         * Resolves the configured (or defaulted) dispatcher-loop count for
         * one named pipeline queue. See {@link com.jio.rcs.operator.queue.QueueNames}.
         */
        public int workersFor(String queueName) {
            Integer override = switch (queueName) {
                case com.jio.rcs.operator.queue.QueueNames.INCOMING -> incomingWorkers;
                case com.jio.rcs.operator.queue.QueueNames.VALIDATION -> validationWorkers;
                case com.jio.rcs.operator.queue.QueueNames.PROCESSING -> processingWorkers;
                case com.jio.rcs.operator.queue.QueueNames.DLR -> dlrWorkers;
                case com.jio.rcs.operator.queue.QueueNames.CALLBACK -> callbackWorkers;
                default -> null;
            };
            return override != null ? Math.max(1, override) : dispatcherWorkersPerQueue();
        }

        /**
         * Default dispatcher-loop count for any stage without its own
         * override - derived from the legacy {@link #workerThreads}
         * property so existing configuration continues to behave the same.
         */
        public int dispatcherWorkersPerQueue() {
            return Math.max(1, workerThreads / 4);
        }

        @Min(value = 1, message = "operator.queue.scheduler-pool-size must be at least 1")
        @Max(value = MAX_REASONABLE_WORKERS, message = "operator.queue.scheduler-pool-size must not exceed " + MAX_REASONABLE_WORKERS)
        private int schedulerPoolSize = 16;
    }

    @Data
    public static class Latency {
        private long minMillis = 100;
        private long maxMillis = 200;
    }

    @Data
    public static class Dlr {
        private Map<String, Long> delaysSeconds;
    }

    @Data
    public static class StateMachine {
        private Map<String, List<String>> transitions;
    }

    @Data
    public static class Probability {
        private int deliveredPercentage = 100;
        private int displayedPercentage = 100;
        private int failedPercentage = 0;
    }

    @Data
    public static class ErrorSimulation {
        private boolean enabled = true;
        private List<ErrorCode> codes;
    }

    @Data
    public static class ErrorCode {
        private String code;
        private String description;
        private int weight;
    }

    @Data
    public static class Callback {
        private long connectTimeoutMillis = 3000;
        private long readTimeoutMillis = 5000;
        private Retry retry = new Retry();
        /**
         * Max total pooled HTTP connections across all webhook destinations.
         * The JDK default client this replaced had no configurable pool at
         * all (effectively ~5 concurrent connections per destination via
         * http.maxConnections), which became the real ceiling on callback
         * throughput once queue backpressure (see InMemoryQueueService) was
         * fixed - a full queue and a connection-starved HTTP client produce
         * the exact same symptom (DLR callbacks falling behind), so both had
         * to be fixed together for genuine zero-loss at high volume.
         */
        private int maxTotalConnections = 200;
        /** Max pooled connections per callback destination (route). */
        private int maxConnectionsPerRoute = 100;
    }

    @Data
    public static class Retry {
        private int maxAttempts = 5;
        private long backoffMillis = 2000;
        private double backoffMultiplier = 2.0;
        /**
         * Upper bound on any single retry's computed backoff, so a message
         * that has already failed a few times doesn't end up waiting minutes
         * between attempts under maxAttempts increases - keeps retry timing
         * predictable at scale.
         */
        private long backoffMaxMillis = 15000;
    }

    @Data
    public static class Bulk {
        private int maxMessagesPerBatch = 1000;
    }

    /**
     * Governs POST /v1/media and GET /media/{id}. This is our own design
     * choice (single file per request, a realistic image/video/file
     * content-type allowlist), not a mirror of any specific real operator's
     * constraints - see README design-philosophy section. Nothing is ever
     * written to disk; uploaded bytes live only in memory until evicted.
     */
    @Data
    public static class Media {
        private List<String> allowedContentTypes = List.of(
                "image/jpeg", "image/jpg", "image/gif", "image/png",
                "video/mp4", "video/mpeg", "video/mpeg4", "video/webm",
                "application/pdf"
        );
        private long maxFileSizeBytes = 10 * 1024 * 1024; // 10MB, our own simulator default
        private long retentionMinutes = 60;
        private long cleanupIntervalMillis = 60_000;
    }

    /**
     * Governs POST /v1/capability/check - a deterministic mock: the same
     * phone number always yields the same result for the life of the
     * process, weighted by capablePercentage.
     */
    @Data
    public static class Capability {
        private int capablePercentage = 90;
    }

    /**
     * Governs the transient, in-memory-only correlation map that lets the
     * async pipeline (and GET /v1/messages/{id}) find an in-flight message.
     * This is not persistence - entries are swept out after retentionMinutes
     * and everything is lost on restart.
     */
    @Data
    public static class MessageStore {
        private long retentionMinutes = 60;
        private long cleanupIntervalMillis = 60_000;
    }
}
