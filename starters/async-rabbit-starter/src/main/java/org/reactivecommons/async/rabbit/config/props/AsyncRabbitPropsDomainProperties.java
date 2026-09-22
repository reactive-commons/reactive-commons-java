package org.reactivecommons.async.rabbit.config.props;

import lombok.NoArgsConstructor;
import org.reactivecommons.async.rabbit.config.RabbitProperties;
import org.reactivecommons.async.starter.props.GenericAsyncPropsDomainProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

@NoArgsConstructor
@ConfigurationProperties(prefix = "app.async")
public class AsyncRabbitPropsDomainProperties extends GenericAsyncPropsDomainProperties<AsyncProps, RabbitProperties> {

    public AsyncRabbitPropsDomainProperties(Map<String, ? extends AsyncProps> m) {
        super(m);
    }

    /**
     * @deprecated in favor of {@link AsyncPropsDomain.RabbitPropsCustomizer}, which allows the same programmatic
     * configuration while keeping the hybrid YAML + programmatic model (YAML values are preserved and only the
     * customized properties are overridden). This builder replaces the whole domain properties, discarding any
     * values already bound from your configuration files.
     */
    @Deprecated(forRemoval = true, since = "7.4.0")
    public static AsyncPropsDomainPropertiesBuilder<AsyncProps, RabbitProperties,
            AsyncRabbitPropsDomainProperties> builder() {
        return GenericAsyncPropsDomainProperties.builder(AsyncRabbitPropsDomainProperties.class);
    }

    @Override
    protected AsyncProps createProps() {
        return new AsyncProps();
    }
}
