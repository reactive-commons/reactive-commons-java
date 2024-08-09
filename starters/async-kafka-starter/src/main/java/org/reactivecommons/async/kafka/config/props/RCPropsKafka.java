package org.reactivecommons.async.kafka.config.props;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;


@Getter
@Setter
@ConfigurationProperties(prefix = "reactive.commons.kafka")
public class RCPropsKafka {

    @NestedConfigurationProperty
    private RCKafkaProps kafkaProps = new RCKafkaProps();

    /**
     * -1 will be considered default value.
     * When withDLQRetry is true, it will be retried 10 times.
     * When withDLQRetry is false, it will be retried indefinitely.
     */
    private Integer maxRetries = -1;

    private Integer retryDelay = 1000;

    private Boolean withDLQRetry = false;
    private Boolean createTopology = true;
}
