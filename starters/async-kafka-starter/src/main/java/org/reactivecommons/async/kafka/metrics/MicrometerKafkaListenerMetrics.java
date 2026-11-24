package org.reactivecommons.async.kafka.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.reactivecommons.async.kafka.listeners.KafkaListenerMetrics;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * The metrics of the messages handled by the listeners of a domain, tagged with {@code domain} and {@code topic}. The
 * names follow those of the RabbitMQ listeners ({@code reactive_commons.consumer.*}), under
 * {@code reactive_commons.kafka} so that both brokers can be used in the same application: a meter name must keep the
 * same tag keys, and the RabbitMQ ones are tagged with {@code queue}.
 * <ul>
 *     <li>{@value #MESSAGES} - Counter, extra tag {@code outcome}: {@code ack} (handled), {@code discard} (failed for
 *     good, logged and skipped) or {@code dlq} (failed for good, sent to its DLQ).</li>
 *     <li>{@value #HANDLER_FAILURES} - Counter, failed attempts of a handler, retried or not.</li>
 *     <li>{@value #RETRY_ATTEMPTS} - Counter, retries of a failed handler.</li>
 *     <li>{@value #RESTART} - Counter, times the listener of the topic stopped consuming and was subscribed again.</li>
 *     <li>{@value #HANDLER_LATENCY} - Timer, from the start of the handling of a message to its outcome, retries
 *     included.</li>
 * </ul>
 * A meter is registered the first time it is used, and removed from the registry once the domain closes. A meter that
 * cannot be registered (e.g. a registry that rejects it) is not recorded: the listener is never failed by its metrics.
 */
@Slf4j
public final class MicrometerKafkaListenerMetrics implements KafkaListenerMetrics {

    static final String MESSAGES = "reactive_commons.kafka.consumer.messages";
    static final String HANDLER_FAILURES = "reactive_commons.kafka.consumer.handler.failures";
    static final String RETRY_ATTEMPTS = "reactive_commons.kafka.consumer.retry.attempts";
    static final String RESTART = "reactive_commons.kafka.consumer.restart";
    static final String HANDLER_LATENCY = "reactive_commons.kafka.consumer.handler.latency";

    private final MeterRegistry registry;
    private final String domain;
    /**
     * The meters registered by this domain, by name and tags, so that each event does not look them up in the
     * registry.
     */
    private final Map<String, Meter> meters = new ConcurrentHashMap<>();
    private volatile boolean closed;

    public MicrometerKafkaListenerMetrics(MeterRegistry registry, String domain) {
        this.registry = registry;
        this.domain = domain;
    }

    @Override
    public void messageDone(String topic, String outcome, Duration took) {
        Counter counter = meter(MESSAGES + "|" + topic + "|" + outcome, () -> Counter.builder(MESSAGES)
                .description("Messages done with, by outcome")
                .tags(tags(topic).and("outcome", outcome))
                .register(registry));
        if (counter != null) {
            counter.increment();
        }
        Timer timer = meter(HANDLER_LATENCY + "|" + topic, () -> Timer.builder(HANDLER_LATENCY)
                .description("From the start of the handling of a message to its outcome, retries included")
                .tags(tags(topic))
                .register(registry));
        if (timer != null) {
            timer.record(took);
        }
    }

    @Override
    public void handlerFailed(String topic) {
        increment(HANDLER_FAILURES, topic, "Failed attempts of a handler");
    }

    @Override
    public void handlerRetried(String topic) {
        increment(RETRY_ATTEMPTS, topic, "Retries of a failed handler");
    }

    @Override
    public void listenerRestarted(String topic) {
        increment(RESTART, topic, "Times the listener stopped consuming and was subscribed again");
    }

    /**
     * Removes the meters of the domain from the registry. Idempotent; nothing is recorded afterwards.
     */
    public void close() {
        closed = true;
        meters.values().forEach(meter -> {
            try {
                registry.remove(meter);
            } catch (RuntimeException e) {
                log.debug("Unable to remove meter {}: {}", meter.getId().getName(), e.toString());
            }
        });
        meters.clear();
    }

    private void increment(String name, String topic, String description) {
        Counter counter = meter(name + "|" + topic, () -> Counter.builder(name)
                .description(description)
                .tags(tags(topic))
                .register(registry));
        if (counter != null) {
            counter.increment();
        }
    }

    private Tags tags(String topic) {
        return Tags.of("domain", domain, "topic", topic);
    }

    @SuppressWarnings("unchecked")
    private <M extends Meter> M meter(String key, Supplier<M> registration) {
        if (closed) {
            return null;
        }
        Meter meter = meters.get(key);
        if (meter == null) {
            try {
                meter = meters.computeIfAbsent(key, ignored -> registration.get());
            } catch (RuntimeException e) {
                log.debug("Unable to register meter {}: {}", key, e.toString());
                return null;
            }
        }
        return (M) meter;
    }
}
