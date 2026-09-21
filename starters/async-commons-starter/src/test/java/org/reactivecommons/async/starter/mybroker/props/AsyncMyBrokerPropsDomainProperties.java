package org.reactivecommons.async.starter.mybroker.props;

import lombok.NoArgsConstructor;
import org.reactivecommons.async.starter.props.GenericAsyncPropsDomainProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

@ConfigurationProperties(prefix = "my.broker")
@NoArgsConstructor
public class AsyncMyBrokerPropsDomainProperties
        extends GenericAsyncPropsDomainProperties<MyBrokerAsyncProps, MyBrokerConnProps> {

    public AsyncMyBrokerPropsDomainProperties(Map<String, ? extends MyBrokerAsyncProps> m) {
        super(m);
    }

}
