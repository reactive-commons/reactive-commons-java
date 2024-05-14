package org.reactivecommons.async.rabbit.config.props;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;


@Getter
@Setter
@ConfigurationProperties(prefix = "app.async")
public class AsyncProps {

    @NestedConfigurationProperty
    private FluxProps flux = new FluxProps();

    @NestedConfigurationProperty
    private DomainProps domain = new DomainProps();

    @NestedConfigurationProperty
    private DirectProps direct = new DirectProps();

    @NestedConfigurationProperty
    private GlobalProps global = new GlobalProps();

    private boolean listenReplies = true;

    /**
     * -1 will be considered default value.
     * When withDLQRetry is true, it will be retried 10 times.
     * When withDLQRetry is false, it will be retried indefinitely.
     */
    private Integer maxRetries = -1;

    private Integer prefetchCount = 250;

    private Integer retryDelay = 1000;

    private Boolean withDLQRetry = false;
    private Boolean createTopology = true; // auto delete queues will always be created and bound
    private Boolean delayedCommands = false;
}
