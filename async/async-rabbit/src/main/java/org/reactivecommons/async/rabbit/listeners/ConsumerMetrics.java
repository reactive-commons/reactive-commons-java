package org.reactivecommons.async.rabbit.listeners;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
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
 *     <li>{@code reactive_commons.consumer.handler.latency} — Timer, around the handler's Mono.</li>
 *     <li>{@code reactive_commons.consumer.handler.wait} — Timer, from the delivery to the start of its handler.</li>
 *     <li>{@code reactive_commons.consumer.restart} — Counter, extra tag {@code reason}
 *     (channel_shutdown, error, cancel).</li>
 *     <li>{@code reactive_commons.consumer.active} — Gauge from the registered {@link ListenerLiveness}.</li>
 *     <li>{@code reactive_commons.consumer.last_message_age_seconds} — Gauge polling the liveness.</li>
 * </ul>
 */
public final class ConsumerMetrics {

    private static final Logger LOG = Logger.getLogger(ConsumerMetrics.class.getName());

    public static final String OUTCOME_ACK = "ack";
    public static final String OUTCOME_NACK = "nack";
    public static final String OUTCOME_DISCARD = "discard";
    public static final String OUTCOME_DLQ = "dlq";
    public static final String OUTCOME_HANDLER_ERROR = "handler_error";

    public static final String REASON_CHANNEL_SHUTDOWN = "channel_shutdown";
    public static final String REASON_ERROR = "error";
    public static final String REASON_CANCEL = "cancel";

    private static final String M_MESSAGES = "reactive_commons.consumer.messages";
    private static final String M_LATENCY = "reactive_commons.consumer.handler.latency";
    private static final String M_WAIT = "reactive_commons.consumer.handler.wait";
    private static final String M_RESTART = "reactive_commons.consumer.restart";
    private static final String M_ACTIVE = "reactive_commons.consumer.active";
    private static final String M_LAST_AGE = "reactive_commons.consumer.last_message_age_seconds";
    private static final String M_REPLIES_LOST = "reactive_commons.consumer.replies.lost";

    private final MeterRegistry registry;
    private final String domain;
    private final String queue;
    private final Tags baseTags;
    private final ConcurrentHashMap<String, Counter> outcomeCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Counter> restartCounters = new ConcurrentHashMap<>();
    private final Timer handlerLatency;
    private final Timer handlerWait;

    public ConsumerMetrics(MeterRegistry registry, String domain, String queue, ListenerLiveness liveness) {
        this.registry = registry;
        this.domain = domain != null ? domain : "";
        this.queue = queue != null ? queue : "";
        this.baseTags = Tags.of("domain", this.domain, "queue", this.queue);
        this.handlerLatency = registry != null ? safeRegisterTimer(M_LATENCY) : null;
        this.handlerWait = registry != null ? safeRegisterTimer(M_WAIT) : null;
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

    public void recordOutcome(String outcome) {
        if (registry == null) {
            return;
        }
        Counter counter = outcomeCounters.computeIfAbsent(outcome, key -> {
            try {
                return Counter.builder(M_MESSAGES).tags(baseTags.and("outcome", key)).register(registry);
            } catch (Exception e) {
                LOG.log(Level.WARNING, e, () -> "Could not register consumer outcome counter");
                return null;
            }
        });
        if (counter != null) {
            counter.increment();
        }
    }

    public void recordRestart(String reason) {
        if (registry == null) {
            return;
        }
        Counter counter = restartCounters.computeIfAbsent(reason, key -> {
            try {
                return Counter.builder(M_RESTART).tags(baseTags.and("reason", key)).register(registry);
            } catch (Exception e) {
                LOG.log(Level.WARNING, e, () -> "Could not register consumer restart counter");
                return null;
            }
        });
        if (counter != null) {
            counter.increment();
        }
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
