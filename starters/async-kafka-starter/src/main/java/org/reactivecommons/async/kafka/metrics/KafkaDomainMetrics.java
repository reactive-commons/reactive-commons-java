package org.reactivecommons.async.kafka.metrics;

import org.jspecify.annotations.Nullable;
import org.reactivecommons.async.kafka.listeners.KafkaListenerMetrics;
import reactor.kafka.receiver.ReceiverOptions;
import reactor.kafka.sender.SenderOptions;

/**
 * What the clients and the listeners of a domain report their metrics to.
 *
 * @param consumerListener binds the metrics of each Kafka consumer while it is open, or {@code null}
 * @param producerListener binds the metrics of the Kafka producer while it is open, or {@code null}
 * @param listenerMetrics  the metrics of the messages handled by the listeners
 * @param onClose          releases the metrics of the domain, once its provider is closed
 */
public record KafkaDomainMetrics(ReceiverOptions.@Nullable ConsumerListener consumerListener,
                                 SenderOptions.@Nullable ProducerListener producerListener,
                                 KafkaListenerMetrics listenerMetrics,
                                 Runnable onClose) {

    /**
     * Records nothing: without Micrometer, or without a {@code MeterRegistry} bean.
     */
    public static final KafkaDomainMetrics NONE = new KafkaDomainMetrics(null, null, KafkaListenerMetrics.NONE,
            () -> {
            });

    /**
     * Releases the metrics of the domain. Idempotent for the metrics of the library.
     */
    public void close() {
        onClose.run();
    }
}
