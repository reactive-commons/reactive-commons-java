package org.reactivecommons.async.kafka.config.props;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.reactivecommons.async.kafka.config.KafkaProperties;
import org.reactivecommons.async.starter.GenericAsyncProps;
import org.springframework.boot.context.properties.NestedConfigurationProperty;


@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@SuperBuilder
public class AsyncKafkaProps extends GenericAsyncProps<KafkaProperties> {

    @NestedConfigurationProperty
    private KafkaProperties connectionProperties = new KafkaProperties();

    @NestedConfigurationProperty
    @Builder.Default
    private DomainProps domain = new DomainProps();

    /**
     * -1 will be considered default value.
     * When withDLQRetry is true, it will be retried 10 times.
     * When withDLQRetry is false, it will be retried indefinitely.
     */
    @Builder.Default
    private Integer maxRetries = -1;

    @Builder.Default
    private Integer retryDelay = 1000;

    @Builder.Default
    private Boolean withDLQRetry = false;
    @Builder.Default
    private Boolean createTopology = true;
    @Builder.Default
    private Boolean checkExistingTopics = true;

}
