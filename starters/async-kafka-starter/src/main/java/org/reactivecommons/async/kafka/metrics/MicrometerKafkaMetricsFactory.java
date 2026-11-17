package org.reactivecommons.async.kafka.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import org.springframework.beans.factory.ObjectProvider;
import reactor.kafka.receiver.MicrometerConsumerListener;
import reactor.kafka.sender.MicrometerProducerListener;

import java.util.List;

/**
 * Publishes in the {@code MeterRegistry} of the application, when there is one, the metrics of the Kafka clients
 * ({@code kafka.consumer.*} and {@code kafka.producer.*}: lag, rates, latencies, errors), bound by reactor-kafka while
 * each client is open and removed once it closes, and those of the listeners (see
 * {@link MicrometerKafkaListenerMetrics}). Every meter is tagged with the {@code domain}.
 */
public class MicrometerKafkaMetricsFactory implements KafkaMetricsFactory {
    private final ObjectProvider<MeterRegistry> registry;

    public MicrometerKafkaMetricsFactory(ObjectProvider<MeterRegistry> registry) {
        this.registry = registry;
    }

    @Override
    public KafkaDomainMetrics forDomain(String domain) {
        MeterRegistry meterRegistry = registry.getIfAvailable();
        if (meterRegistry == null) {
            return KafkaDomainMetrics.NONE;
        }
        List<Tag> tags = List.of(Tag.of("domain", domain));
        MicrometerKafkaListenerMetrics listenerMetrics = new MicrometerKafkaListenerMetrics(meterRegistry, domain);
        return new KafkaDomainMetrics(new MicrometerConsumerListener(meterRegistry, tags),
                new MicrometerProducerListener(meterRegistry, tags), listenerMetrics, listenerMetrics::close);
    }
}
