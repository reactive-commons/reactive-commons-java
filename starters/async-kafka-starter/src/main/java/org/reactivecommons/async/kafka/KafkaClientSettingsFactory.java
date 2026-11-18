package org.reactivecommons.async.kafka;

import org.reactivecommons.async.kafka.config.KafkaReceiverOptionsCustomizer;
import org.reactivecommons.async.kafka.config.KafkaSenderOptionsCustomizer;
import org.reactivecommons.async.kafka.config.props.AsyncKafkaProps;
import org.reactivecommons.async.kafka.metrics.KafkaDomainMetrics;
import org.reactivecommons.async.kafka.metrics.KafkaMetricsFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ssl.SslBundles;

/**
 * Composes the {@link KafkaClientSettings} of each domain from the beans of the application: its SSL bundles, its
 * options customizers and its metrics.
 */
public final class KafkaClientSettingsFactory {
    private final ObjectProvider<SslBundles> sslBundles;
    private final ObjectProvider<KafkaReceiverOptionsCustomizer> receiverCustomizers;
    private final ObjectProvider<KafkaSenderOptionsCustomizer> senderCustomizers;
    private final ObjectProvider<KafkaMetricsFactory> metricsFactory;

    /**
     * @param sslBundles          resolves {@code ssl.bundle} of the connection properties
     * @param receiverCustomizers applied, in order, to the options of the consumers of every domain
     * @param senderCustomizers   applied, in order, to the options of the producer of every domain
     * @param metricsFactory      the metrics of each domain; none when absent
     */
    public KafkaClientSettingsFactory(ObjectProvider<SslBundles> sslBundles,
                                      ObjectProvider<KafkaReceiverOptionsCustomizer> receiverCustomizers,
                                      ObjectProvider<KafkaSenderOptionsCustomizer> senderCustomizers,
                                      ObjectProvider<KafkaMetricsFactory> metricsFactory) {
        this.sslBundles = sslBundles;
        this.receiverCustomizers = receiverCustomizers;
        this.senderCustomizers = senderCustomizers;
        this.metricsFactory = metricsFactory;
    }

    /**
     * The settings the clients of the domain are built with. Each call gives the domain a new {@code client.id} base
     * and, when there is a {@link KafkaMetricsFactory}, new metrics.
     *
     * @param domain the domain, given to the customizers and to the metrics
     * @param props  the properties of the domain, which the {@code client.id} base is taken from
     * @return the settings of the clients of the domain
     */
    public KafkaClientSettings forDomain(String domain, AsyncKafkaProps props) {
        KafkaMetricsFactory factory = metricsFactory.getIfAvailable();
        KafkaDomainMetrics metrics = factory == null ? KafkaDomainMetrics.NONE : factory.forDomain(domain);
        return new KafkaClientSettings(domain, KafkaClientSettings.clientIdBase(props), sslBundles.getIfAvailable(),
                receiverCustomizers.orderedStream().toList(), senderCustomizers.orderedStream().toList(), metrics);
    }
}
