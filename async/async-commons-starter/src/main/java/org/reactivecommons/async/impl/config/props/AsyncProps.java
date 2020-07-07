package org.reactivecommons.async.impl.config.props;

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

    private Integer maxRetries = 10;

    private Integer prefetchCount = 250;

    private Integer retryDelay = 1000;

    private Boolean withDLQRetry = false;
}
