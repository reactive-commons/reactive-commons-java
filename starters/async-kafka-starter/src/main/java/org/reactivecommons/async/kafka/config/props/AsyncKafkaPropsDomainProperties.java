package org.reactivecommons.async.kafka.config.props;

import lombok.NoArgsConstructor;
import org.reactivecommons.async.kafka.config.KafkaProperties;
import org.reactivecommons.async.starter.props.GenericAsyncPropsDomainProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;

@NoArgsConstructor
@ConfigurationProperties(prefix = "reactive.commons.kafka")
public class AsyncKafkaPropsDomainProperties extends GenericAsyncPropsDomainProperties<AsyncKafkaProps, KafkaProperties> {

    /**
     * @deprecated in favor of {@link AsyncKafkaPropsDomain.KafkaPropsCustomizer}, which allows the same programmatic
     * configuration while keeping the hybrid YAML + programmatic model (YAML values are preserved and only the
     * customized properties are overridden). This builder replaces the whole domain properties, discarding any
     * values already bound from your configuration files.
     */
    @Deprecated(forRemoval = true, since = "7.4.0")
    public static AsyncPropsDomainPropertiesBuilder<AsyncKafkaProps, KafkaProperties,
            AsyncKafkaPropsDomainProperties> builder() {
        return GenericAsyncPropsDomainProperties.builder(AsyncKafkaPropsDomainProperties.class);
    }

    @Override
    protected AsyncKafkaProps createProps() {
        return new AsyncKafkaProps();
    }
}
