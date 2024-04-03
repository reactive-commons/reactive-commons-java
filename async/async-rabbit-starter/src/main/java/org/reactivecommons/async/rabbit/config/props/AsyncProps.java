package org.reactivecommons.async.rabbit.config.props;

import lombok.Builder;
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

    private Integer maxRetries = 10;

    private Integer prefetchCount = 250;

    private Integer retryDelay = 1000;

    private Boolean withDLQRetry = false;
    private Boolean createTopology = true; // auto delete queues will always be created and bound
    private Boolean delayedCommands = false;
}
