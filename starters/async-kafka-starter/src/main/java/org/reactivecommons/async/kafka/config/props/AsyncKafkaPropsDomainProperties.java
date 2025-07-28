package org.reactivecommons.async.kafka.config.props;

import lombok.NoArgsConstructor;
import org.reactivecommons.async.kafka.config.KafkaProperties;
import org.reactivecommons.async.starter.props.GenericAsyncPropsDomainProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

@NoArgsConstructor
@ConfigurationProperties(prefix = "reactive.commons.kafka")
public class AsyncKafkaPropsDomainProperties extends GenericAsyncPropsDomainProperties<AsyncKafkaProps, KafkaProperties> {

    public AsyncKafkaPropsDomainProperties(Map<String, ? extends AsyncKafkaProps> m) {
        super(m);
    }

    public static AsyncPropsDomainPropertiesBuilder<AsyncKafkaProps, KafkaProperties,
            AsyncKafkaPropsDomainProperties> builder() {
        return GenericAsyncPropsDomainProperties.builder(AsyncKafkaPropsDomainProperties.class);
    }
}
