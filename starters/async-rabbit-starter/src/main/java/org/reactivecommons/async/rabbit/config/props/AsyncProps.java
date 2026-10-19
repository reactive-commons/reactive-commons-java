package org.reactivecommons.async.rabbit.config.props;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.reactivecommons.async.commons.config.IBrokerConfigProps;
import org.reactivecommons.async.rabbit.config.RabbitProperties;
import org.reactivecommons.async.starter.props.GenericAsyncProps;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import java.time.Duration;


@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@SuperBuilder
public class AsyncProps extends GenericAsyncProps<RabbitProperties> {
    @NestedConfigurationProperty
    @Builder.Default
    private FluxProps flux = new FluxProps();

    @NestedConfigurationProperty
    @Builder.Default
    private DomainProps domain = new DomainProps();

    @NestedConfigurationProperty
    @Builder.Default
    private DirectProps direct = new DirectProps();

    @NestedConfigurationProperty
    @Builder.Default
    private GlobalProps global = new GlobalProps();

    @NestedConfigurationProperty
    private RabbitProperties connectionProperties;

    private IBrokerConfigProps brokerConfigProps;

    /**
     * -1 will be considered default value.
     * When withDLQRetry is true, it will be retried 10 times.
     * When withDLQRetry is false, it will be retried indefinitely.
     */
    @Builder.Default
    private Integer maxRetries = -1;

    @Builder.Default
    private Integer prefetchCount = 250;

    @Builder.Default
    private Integer retryDelay = 1000;

    /**
     * Maximum time a sender waits for the outcome of a message before failing the caller: a message that could not be
     * handed to a publishing flow fails with {@code SendFailureNoAckException}, and a message handed to a flow but not
     * confirmed in time fails with {@code SendConfirmUnknownException} (it may or may not have reached the broker).
     */
    @Builder.Default
    private Duration sendTimeout = Duration.ofSeconds(10);

    /**
     * Size of the publish hand-over thread pool: these threads hand each message to a publishing flow and can block
     * inside {@code basicPublish} when the broker raises a resource alarm. Keep it small; back-pressure is
     * surfaced through {@link #handOverQueueCapacity}.
     */
    @Builder.Default
    private int handOverPoolSize = 13;

    /**
     * Maximum number of messages waiting to be written to a channel: queued in front of the publish hand-over pool,
     * and handed to a publishing flow but not written yet (e.g. while the broker blocks the connection). When reached,
     * new publishes fail fast with {@code SendFailureHandOverRejectedException} instead of queueing in memory while
     * the broker is back-pressuring.
     */
    @Builder.Default
    private int handOverQueueCapacity = 1024;

    /**
     * How long a {@code connection.blocked} notification must persist before the health indicator reports DOWN. A
     * short alarm (e.g. a transient disk flush) should not flap the health check.
     */
    @Builder.Default
    private Duration blockedHealthGrace = Duration.ofSeconds(5);

    @Builder.Default
    private Boolean listenReplies = null;

    public Boolean getListenReplies() {
        if (listenReplies == null) {
            throw new IllegalArgumentException("The 'listenReplies' property is required, please specify a 'true' or 'false' value.");
        }
        return listenReplies;
    }

    @Builder.Default
    private Boolean withDLQRetry = false;
    @Builder.Default
    private Boolean delayedCommands = false;
    @Builder.Default
    private Boolean createTopology = true; // auto delete queues will always be created and bound

    @Builder.Default
    private boolean useDiscardNotifierPerDomain = false;

    @Builder.Default
    private Boolean mandatory = false;

    @Builder.Default
    private boolean enabled = true;

    @Builder.Default
    private String brokerType = "rabbitmq";

    @Builder.Default
    private String queueType = null; // "classic" or "quorum"

}
