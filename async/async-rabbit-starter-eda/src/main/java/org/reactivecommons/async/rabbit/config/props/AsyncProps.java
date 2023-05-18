package org.reactivecommons.async.rabbit.config.props;

import lombok.Getter;
import lombok.Setter;
import org.reactivecommons.async.rabbit.config.RabbitProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;


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

    @NestedConfigurationProperty
    private NotificationProps notificationProps = new NotificationProps();

    @NestedConfigurationProperty
    private Map<String, RabbitProperties> connections = new TreeMap<>();

    private Integer maxRetries = 10;

    private Integer prefetchCount = 250;

    private Integer retryDelay = 1000;

    private Boolean withDLQRetry = false;

}
