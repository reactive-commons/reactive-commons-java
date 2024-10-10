package org.reactivecommons.async.starter.impl.mybroker;

import org.reactivecommons.async.starter.mybroker.MyBrokerProviderFactory;
import org.reactivecommons.async.starter.mybroker.MyBrokerSecretFiller;
import org.reactivecommons.async.starter.mybroker.props.AsyncMyBrokerPropsDomainProperties;
import org.reactivecommons.async.starter.mybroker.props.MyBrokerAsyncPropsDomain;
import org.reactivecommons.async.starter.mybroker.props.MyBrokerConnProps;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

@EnableConfigurationProperties(AsyncMyBrokerPropsDomainProperties.class)
@Import({MyBrokerAsyncPropsDomain.class, MyBrokerProviderFactory.class})
public class MyBrokerConfig {

    @Bean
    public MyBrokerConnProps defaultMyBrokerConnProps() {
        MyBrokerConnProps myBrokerConnProps = new MyBrokerConnProps();
        myBrokerConnProps.setHost("localhost");
        myBrokerConnProps.setPort("1234");
        return myBrokerConnProps;
    }

    @Bean
    public MyBrokerSecretFiller defaultMyBrokerSecretFiller() {
        return (domain, props) -> {
        };
    }
}
