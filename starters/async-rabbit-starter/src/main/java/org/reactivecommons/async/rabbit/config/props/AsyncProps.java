package org.reactivecommons.async.rabbit.config.props;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.reactivecommons.async.commons.config.IBrokerConfigProps;
import org.reactivecommons.async.rabbit.config.RabbitProperties;
import org.reactivecommons.async.starter.GenericAsyncProps;
import org.springframework.boot.context.properties.NestedConfigurationProperty;


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

    @Builder.Default
    private boolean listenReplies = true;

    @Builder.Default
    private Boolean withDLQRetry = false;
    @Builder.Default
    private Boolean delayedCommands = false;
    @Builder.Default
    private Boolean createTopology = true; // auto delete queues will always be created and bound

}
