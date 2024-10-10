package org.reactivecommons.async.starter.mybroker.props;

import org.reactivecommons.async.starter.props.GenericAsyncPropsDomainProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

@ConfigurationProperties(prefix = "my.broker")
public class AsyncMyBrokerPropsDomainProperties
        extends GenericAsyncPropsDomainProperties<MyBrokerAsyncProps, MyBrokerConnProps> {

    public AsyncMyBrokerPropsDomainProperties() {
    }

    public AsyncMyBrokerPropsDomainProperties(Map<String, ? extends MyBrokerAsyncProps> m) {
        super(m);
    }

    public static AsyncPropsDomainPropertiesBuilder<MyBrokerAsyncProps, MyBrokerConnProps,
            AsyncMyBrokerPropsDomainProperties> builder() {
        return GenericAsyncPropsDomainProperties.builder(AsyncMyBrokerPropsDomainProperties.class);
    }
}