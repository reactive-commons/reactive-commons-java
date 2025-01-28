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

    public static AsyncPropsDomainPropertiesBuilder<AsyncProps, RabbitProperties,
            AsyncRabbitPropsDomainProperties> builder() {
        return GenericAsyncPropsDomainProperties.builder(AsyncRabbitPropsDomainProperties.class);
    }
}
