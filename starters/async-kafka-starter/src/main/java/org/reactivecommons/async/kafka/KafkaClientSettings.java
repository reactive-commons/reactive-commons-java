package org.reactivecommons.async.kafka;

import org.jspecify.annotations.Nullable;
import org.reactivecommons.async.kafka.config.KafkaReceiverOptionsCustomizer;
import org.reactivecommons.async.kafka.config.KafkaSenderOptionsCustomizer;
import org.reactivecommons.async.kafka.config.props.AsyncKafkaProps;
import org.reactivecommons.async.kafka.metrics.KafkaDomainMetrics;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * What the Kafka clients of a domain are built with besides the properties of the domain.
 *
 * @param domain              the domain, given to the customizers
 * @param clientIdBase        the base of the {@code client.id} of every client of the domain (see
 *                            {@link #clientIdBase(AsyncKafkaProps)})
 * @param sslBundles          resolves {@code ssl.bundle}; {@code null} when there are no bundles
 * @param receiverCustomizers applied to the options of the consumers, in order
 * @param senderCustomizers   applied to the options of the producer, in order
 * @param metrics             what the clients and the listeners report to
 */
public record KafkaClientSettings(String domain, String clientIdBase, @Nullable SslBundles sslBundles,
                                  List<KafkaReceiverOptionsCustomizer> receiverCustomizers,
                                  List<KafkaSenderOptionsCustomizer> senderCustomizers,
                                  KafkaDomainMetrics metrics) {

    private static final String DEFAULT_CLIENT_ID = "reactive-commons";

    public KafkaClientSettings {
        receiverCustomizers = List.copyOf(receiverCustomizers);
        senderCustomizers = List.copyOf(senderCustomizers);
    }

    /**
     * Without SSL bundles, customizers nor metrics.
     */
    public static KafkaClientSettings defaults(String domain, AsyncKafkaProps props) {
        return new KafkaClientSettings(domain, clientIdBase(props), null, List.of(), List.of(),
                KafkaDomainMetrics.NONE);
    }

    /**
     * The configured {@code client-id} of the connection properties, or else the {@code appName}, followed by a short
     * suffix of its own: each instance of the application, and each domain, gets {@code client.id} values of its own,
     * so their JMX beans and metrics do not collide and the broker tells them apart in its logs and quotas.
     */
    public static String clientIdBase(AsyncKafkaProps props) {
        String configured = props.getConnectionProperties().getClientId();
        String base;
        if (StringUtils.hasText(configured)) {
            base = configured;
        } else if (StringUtils.hasText(props.getAppName())) {
            base = props.getAppName();
        } else {
            base = DEFAULT_CLIENT_ID;
        }
        return base + "-" + Integer.toHexString(ThreadLocalRandom.current().nextInt(0x100000, 0x1000000));
    }

    /**
     * {@code <base>-producer}.
     */
    public String producerClientId() {
        return clientIdBase + "-producer";
    }

    /**
     * {@code <base>-consumer}: each consumer adds {@code -<n>} to it.
     */
    public String consumerClientId() {
        return clientIdBase + "-consumer";
    }

    /**
     * {@code <base>-admin}.
     */
    public String adminClientId() {
        return clientIdBase + "-admin";
    }
}
