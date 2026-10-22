package org.reactivecommons.async.rabbit.listeners;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.binder.BaseUnits;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Micrometer counters, a timer and gauges on the consumer path. Mirrors the publisher-side {@code PublishMetrics}:
 * counters are cached per (queue, outcome) to avoid a {@code registry.find} on every message, and registration is
 * wrapped in a try/catch so a mis-wired or mocked {@link MeterRegistry} never breaks the listener.
 *
 * <p>Metrics (all tagged with {@code domain} + {@code queue}):
 * <ul>
 *     <li>{@code reactive_commons.consumer.messages} — Counter, extra tag {@code outcome} ∈
 *     {ack, nack, discard, dlq, handler_error}.</li>
 *     <li>{@code reactive_commons.consumer.handler.latency} — Timer, around the handler's Mono. End-to-end:
 *     includes handler-pool wait, every local retry and the retry delays.</li>
 *     <li>{@code reactive_commons.consumer.handler.execution} — Timer, per attempt around {@code handler.apply}.
 *     Extra tags: {@code outcome} ∈ {success, error, timeout} and {@code handler} (the executor path, bounded by
 *     {@link GenericMessageListener#MAX_CACHED_HANDLERS}; unregistered paths bucket to {@code unknown}). Compare
 *     against {@code handler.latency} to split handler-pool wait from handler duration, and against
 *     {@code handlerTimeout} to see how close attempts run to the per-attempt deadline.</li>
 *     <li>{@code reactive_commons.consumer.handler.wait} — Timer, from the delivery to the start of its handler.</li>
 *     <li>{@code reactive_commons.consumer.restart} — Counter, extra tag {@code reason}
 *     (channel_shutdown, error, cancel).</li>
 *     <li>{@code reactive_commons.consumer.active} — Gauge from the registered {@link ListenerLiveness}.</li>
 *     <li>{@code reactive_commons.consumer.last_message_age_seconds} — Gauge polling the liveness.</li>
 *     <li>{@code reactive_commons.consumer.retry.attempts} — Counter, local retries of a failed handler.</li>
 *     <li>{@code reactive_commons.consumer.retry.exhausted} — Counter, local retries exhausted.</li>
 *     <li>{@code reactive_commons.consumer.redelivered} — Counter, deliveries of a message delivered before: requeued
 *     (AMQP {@code redelivered} flag) or dead-lettered back from the retry queue ({@code x-death} count &gt; 0).</li>
 *     <li>{@code reactive_commons.consumer.handler.timeout} — Counter, handlers that exceeded
 *     {@code handlerTimeout}.</li>
 *     <li>{@code reactive_commons.consumer.query.discarded} — Counter, extra tag {@code reason}
 *     (timeout): queries discarded because their caller stopped waiting.</li>
 *     <li>{@code reactive_commons.consumer.query.reply.failed} — Counter, query replies that could not be
 *     published.</li>
 *     <li>{@code reactive_commons.consumer.discard.notify.failed} — Counter, discards that could not be notified
 *     (the message is requeued).</li>
 *     <li>{@code reactive_commons.consumer.query.invalid.timeout_header} — Counter, invalid reply timeout
 *     headers ignored.</li>
 *     <li>{@code reactive_commons.consumer.message.size} — DistributionSummary, bytes of each consumed body.</li>
 *     <li>{@code reactive_commons.consumer.in_flight} — Gauge, deliveries received and not acknowledged (acked or
 *     nacked) yet.</li>
 * </ul>
 *
 * <p>The {@code messages} outcomes and the {@code restart} reasons are registered at zero with the listener, so a
 * failure series exists (and {@code increase()} counts its first occurrence) before the first failure happens.
 */
public final class ConsumerMetrics {

    private static final Logger LOG = Logger.getLogger(ConsumerMetrics.class.getName());

    public static final String OUTCOME_ACK = "ack";
    public static final String OUTCOME_NACK = "nack";
    public static final String OUTCOME_DISCARD = "discard";
    public static final String OUTCOME_DLQ = "dlq";
    public static final String OUTCOME_HANDLER_ERROR = "handler_error";

    public static final String EXECUTION_SUCCESS = "success";
    public static final String EXECUTION_ERROR = "error";
    public static final String EXECUTION_TIMEOUT = "timeout";
    public static final String UNKNOWN_HANDLER = "unknown";

    public static final String REASON_CHANNEL_SHUTDOWN = "channel_shutdown";
    public static final String REASON_ERROR = "error";
    public static final String REASON_CANCEL = "cancel";
    public static final String REASON_TIMEOUT = "timeout";

    private static final String M_MESSAGES = "reactive_commons.consumer.messages";
    private static final String M_LATENCY = "reactive_commons.consumer.handler.latency";
    private static final String M_EXECUTION = "reactive_commons.consumer.handler.execution";
    private static final String M_WAIT = "reactive_commons.consumer.handler.wait";
    private static final String M_RESTART = "reactive_commons.consumer.restart";
    private static final String M_ACTIVE = "reactive_commons.consumer.active";
    private static final String M_LAST_AGE = "reactive_commons.consumer.last_message_age_seconds";
    private static final String M_REPLIES_LOST = "reactive_commons.consumer.replies.lost";
    private static final String M_RETRY_ATTEMPTS = "reactive_commons.consumer.retry.attempts";
    private static final String M_RETRY_EXHAUSTED = "reactive_commons.consumer.retry.exhausted";
    private static final String M_REDELIVERED = "reactive_commons.consumer.redelivered";
    private static final String M_HANDLER_TIMEOUT = "reactive_commons.consumer.handler.timeout";
    private static final String M_QUERY_DISCARDED = "reactive_commons.consumer.query.discarded";
    private static final String M_QUERY_REPLY_FAILED = "reactive_commons.consumer.query.reply.failed";
    private static final String M_DISCARD_NOTIFY_FAILED = "reactive_commons.consumer.discard.notify.failed";
    private static final String M_INVALID_TIMEOUT_HEADER = "reactive_commons.consumer.query.invalid.timeout_header";
    private static final String M_MESSAGE_SIZE = "reactive_commons.consumer.message.size";
    private static final String M_IN_FLIGHT = "reactive_commons.consumer.in_flight";
    private static final List<String> OUTCOMES =
            List.of(OUTCOME_ACK, OUTCOME_NACK, OUTCOME_DISCARD, OUTCOME_DLQ, OUTCOME_HANDLER_ERROR);
    private static final List<String> RESTART_REASONS = List.of(REASON_CHANNEL_SHUTDOWN, REASON_ERROR, REASON_CANCEL);

    private final MeterRegistry registry;
    private final String domain;
    private final String queue;
    private final Tags baseTags;
    private final ConcurrentHashMap<String, Counter> outcomeCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> restartCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Timer> executionTimers = new ConcurrentHashMap<>();
    private final Timer handlerLatency;
    private final Timer handlerWait;
    private final Counter retryAttempts;
    private final Counter retriesExhausted;
    private final Counter redelivered;
    private final Counter handlerTimeout;
    private final Counter queryDiscardedTimeout;
    private final Counter queryReplyFailed;
    private final Counter discardNotifyFailed;
    private final Counter invalidTimeoutHeader;
    private final DistributionSummary messageSize;
    private final AtomicInteger inFlight = new AtomicInteger(); // strongly held: Micrometer keeps gauges weakly

    public ConsumerMetrics(MeterRegistry registry, String domain, String queue, ListenerLiveness liveness) {
        this.registry = registry;
        this.domain = domain != null ? domain : "";
        this.queue = queue != null ? queue : "";
        this.baseTags = Tags.of("domain", this.domain, "queue", this.queue);
        this.handlerLatency = registry != null ? safeRegisterTimer(M_LATENCY) : null;
        this.handlerWait = registry != null ? safeRegisterTimer(M_WAIT) : null;
        this.retryAttempts = registry != null ? safeRegisterCounter(M_RETRY_ATTEMPTS) : null;
        this.retriesExhausted = registry != null ? safeRegisterCounter(M_RETRY_EXHAUSTED) : null;
        this.redelivered = registry != null ? safeRegisterCounter(M_REDELIVERED) : null;
        this.handlerTimeout = registry != null ? safeRegisterCounter(M_HANDLER_TIMEOUT) : null;
        this.queryDiscardedTimeout = registry != null
                ? safeRegisterCounter(M_QUERY_DISCARDED, Tags.of("reason", REASON_TIMEOUT)) : null;
        this.queryReplyFailed = registry != null ? safeRegisterCounter(M_QUERY_REPLY_FAILED) : null;
        this.discardNotifyFailed = registry != null ? safeRegisterCounter(M_DISCARD_NOTIFY_FAILED) : null;
        this.invalidTimeoutHeader = registry != null ? safeRegisterCounter(M_INVALID_TIMEOUT_HEADER) : null;
        this.messageSize = registry != null ? safeRegisterSummary(M_MESSAGE_SIZE) : null;
        if (registry != null) {
            OUTCOMES.forEach(this::outcomeCounter);
            RESTART_REASONS.forEach(this::restartCounter);
            try {
                registry.gauge(M_IN_FLIGHT, baseTags, inFlight);
            } catch (Exception e) {
                LOG.log(Level.WARNING, e, () -> "Could not register consumer in-flight gauge");
            }
        }
        if (registry != null && liveness != null) {
            try {
                registry.gauge(M_ACTIVE, baseTags, liveness, l -> l.isActive() ? 1.0 : 0.0);
                registry.gauge(M_LAST_AGE, baseTags, liveness, l -> {
                    Instant last = l.snapshot().lastDeliveryAt();
                    return last == null ? Double.NaN : Duration.between(last, Instant.now()).toMillis() / 1000.0;
                });
            } catch (Exception e) {
                LOG.log(Level.WARNING, e, () -> "Could not register consumer gauges");
            }
        }
    }

    private Timer safeRegisterTimer(String name) {
        try {
            return Timer.builder(name).tags(baseTags).register(registry);
        } catch (Exception e) {
            LOG.log(Level.WARNING, e, () -> "Could not register consumer handler timer");
            return null;
        }
    }

    private Counter safeRegisterCounter(String name) {
        return safeRegisterCounter(name, Tags.empty());
    }

    private Counter safeRegisterCounter(String name, Tags extraTags) {
        try {
            return Counter.builder(name).tags(baseTags.and(extraTags)).register(registry);
        } catch (Exception e) {
            LOG.log(Level.WARNING, e, () -> "Could not register consumer counter " + name);
            return null;
        }
    }

    private DistributionSummary safeRegisterSummary(String name) {
        try {
            return DistributionSummary.builder(name).baseUnit(BaseUnits.BYTES).tags(baseTags).register(registry);
        } catch (Exception e) {
            LOG.log(Level.WARNING, e, () -> "Could not register consumer summary " + name);
            return null;
        }
    }

    public void recordOutcome(String outcome) {
        if (registry == null) {
            return;
        }
        increment(outcomeCounter(outcome));
    }

    public void recordRestart(String reason) {
        if (registry == null) {
            return;
        }
        increment(restartCounter(reason));
    }

    private Counter outcomeCounter(String outcome) {
        return outcomeCounters.computeIfAbsent(outcome,
                key -> safeRegisterCounter(M_MESSAGES, Tags.of("outcome", key)));
    }

    private Counter restartCounter(String reason) {
        return restartCounters.computeIfAbsent(reason, key -> safeRegisterCounter(M_RESTART, Tags.of("reason", key)));
    }

    /**
     * A delivery was received: it is in flight until {@link #recordDeliveryFinished()}.
     */
    public void recordDeliveryStarted() {
        inFlight.incrementAndGet();
    }

    /**
     * A delivery was acknowledged or nacked, or abandoned because its listener stopped (the broker requeues it).
     */
    public void recordDeliveryFinished() {
        inFlight.decrementAndGet();
    }

    public Timer handlerLatency() {
        return handlerLatency;
    }

    /**
     * Records how long a delivery waited for a handler thread (the pool is shared by every listener).
     */
    public void recordHandlerWait(Duration wait) {
        if (handlerWait != null) {
            handlerWait.record(wait);
        }
    }

    /**
     * Records a per-attempt handler execution. Each local retry is a new attempt and gets its own sample, so this
     * metric does not include retry delays and can be compared directly against {@code handlerTimeout}.
     */
    public void recordHandlerExecution(String handler, String outcome, Duration took) {
        if (registry == null) {
            return;
        }
        final Timer timer = executionTimer(outcome, handler != null ? handler : UNKNOWN_HANDLER);
        if (timer != null) {
            timer.record(took);
        }
    }

    private Timer executionTimer(String outcome, String handler) {
        return executionTimers.computeIfAbsent(outcome + '|' + handler, key -> {
            try {
                return Timer.builder(M_EXECUTION)
                        .tags(baseTags.and("outcome", outcome).and("handler", handler))
                        .register(registry);
            } catch (Exception e) {
                LOG.log(Level.WARNING, e, () -> "Could not register consumer handler execution timer");
                return null;
            }
        });
    }

    public void recordHandlerRetry() {
        increment(retryAttempts);
    }

    public void recordRetriesExhausted() {
        increment(retriesExhausted);
    }

    public void recordRedelivered() {
        increment(redelivered);
    }

    public void recordHandlerTimeout() {
        increment(handlerTimeout);
    }

    /**
     * A query whose caller stopped waiting for its reply, discarded instead of retried or run.
     */
    public void recordQueryDiscardedByTimeout() {
        increment(queryDiscardedTimeout);
    }

    public void recordQueryReplyFailed() {
        increment(queryReplyFailed);
    }

    public void recordDiscardNotificationFailed() {
        increment(discardNotifyFailed);
    }

    public void recordInvalidTimeoutHeader() {
        increment(invalidTimeoutHeader);
    }

    public void recordMessageSize(long bytes) {
        if (messageSize != null) {
            messageSize.record(bytes);
        }
    }

    private void increment(Counter counter) {
        if (counter != null) {
            counter.increment();
        }
    }

    /**
     * Registers (idempotently) a counter {@code reactive_commons.consumer.replies.lost} tagged with the given
     * domain and increments it by {@code count}. Called by {@code ApplicationReplyListener} when the reply
     * listener restarts and in-flight correlations are failed.
     */
    public static void recordRepliesLost(MeterRegistry registry, String domain, long count) {
        if (registry == null || count <= 0) {
            return;
        }
        try {
            Counter.builder(M_REPLIES_LOST)
                    .tags(Tags.of("domain", domain != null ? domain : ""))
                    .register(registry)
                    .increment(count);
        } catch (Exception e) {
            LOG.log(Level.WARNING, e, () -> "Could not register consumer replies.lost counter");
        }
    }
}
