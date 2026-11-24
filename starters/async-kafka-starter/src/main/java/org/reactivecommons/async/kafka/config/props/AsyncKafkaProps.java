package org.reactivecommons.async.kafka.config.props;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.reactivecommons.async.kafka.communications.ReactiveMessageSender;
import org.reactivecommons.async.kafka.config.KafkaProperties;
import org.reactivecommons.async.kafka.health.KafkaReactiveHealthIndicator;
import org.reactivecommons.async.starter.props.GenericAsyncProps;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import java.time.Duration;


@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@SuperBuilder
public class AsyncKafkaProps extends GenericAsyncProps<KafkaProperties> {

    @NestedConfigurationProperty
    @Builder.Default
    private KafkaProperties connectionProperties = new KafkaProperties();

    @NestedConfigurationProperty
    @Builder.Default
    private DomainProps domain = new DomainProps();

    /**
     * Apicurio Registry schema validation of this domain. Only read when the
     * {@code async-commons-kafka-apicurio-starter} dependency is present.
     */
    @NestedConfigurationProperty
    @Builder.Default
    private ApicurioValidationProperties apicurio = new ApicurioValidationProperties();

    /**
     * -1 will be considered default value.
     * When withDLQRetry is true, it will be retried 10 times.
     * When withDLQRetry is false, it will be retried indefinitely.
     */
    @Builder.Default
    private Integer maxRetries = -1;

    @Builder.Default
    private Integer retryDelay = 1000;

    /**
     * Maximum time a send waits for the broker to acknowledge its record before failing with a
     * {@code java.util.concurrent.TimeoutException} (the record may still be written). When the producer property
     * {@code max.block.ms} is not set, it is given this value instead of the 60 s of the client, so that a topic
     * without metadata or an unreachable broker does not hold the send sequence a minute per record.
     */
    @Builder.Default
    private Duration sendTimeout = ReactiveMessageSender.DEFAULT_SEND_TIMEOUT;

    /**
     * Maximum number of sends waiting for their outcome at once (queued for the producer or handed to it and not
     * acknowledged yet). Beyond it a send fails at once with {@code SendQueueFullException}, instead of piling up in
     * memory while the broker cannot be reached.
     */
    @Builder.Default
    private Integer maxPendingSends = ReactiveMessageSender.DEFAULT_MAX_PENDING_SENDS;

    /**
     * How long the health check of the domain waits for the cluster to answer before reporting it {@code DOWN}. Keep
     * it well below the timeout of the probes that read the health, and below the 10 s after which Spring Boot reports
     * a slow health indicator.
     */
    @Builder.Default
    private Duration healthTimeout = KafkaReactiveHealthIndicator.DEFAULT_TIMEOUT;

    @Builder.Default
    private Boolean withDLQRetry = false;
    @Builder.Default
    private Boolean createTopology = true;
    @Builder.Default
    private Boolean checkExistingTopics = true;

    @Builder.Default
    private boolean useDiscardNotifierPerDomain = false;

    @Builder.Default
    private boolean enabled = true;

    @Builder.Default
    private String brokerType = "kafka";

    private static final String EVENTS_GROUP_ID_SUFFIX = "-events";

    /**
     * Resolves the consumer group id used by the domain events listener.
     * <p>
     * It takes the <code>group.id</code> configured in the consumer connection properties, and falls back to
     * <code>appName + "-events"</code> when it is not present.
     *
     * @return the consumer group id for the domain events listener
     */
    public String resolveEventsGroupId() {
        Object configured = getConnectionProperties() == null ? null
                : getConnectionProperties().buildConsumerProperties().get(ConsumerConfig.GROUP_ID_CONFIG);
        if (configured instanceof String groupId && !groupId.isBlank()) {
            return groupId;
        }
        return getAppName() + EVENTS_GROUP_ID_SUFFIX;
    }

    /**
     * Resolves the consumer group id used by a raw topic listener registered with
     * {@code HandlerRegistry.listenTopic(...)}.
     *
     * @return the consumer group id for the topic listeners
     */
    public String resolveTopicListenerGroupId() {
        Object configured = getConnectionProperties() == null ? null
                : getConnectionProperties().buildConsumerProperties().get(ConsumerConfig.GROUP_ID_CONFIG);
        if (configured instanceof String groupId && !groupId.isBlank()) {
            return groupId;
        }
        return getAppName();
    }
}
