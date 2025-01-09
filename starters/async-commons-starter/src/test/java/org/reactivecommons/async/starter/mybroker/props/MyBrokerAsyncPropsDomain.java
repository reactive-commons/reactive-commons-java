package org.reactivecommons.async.starter.mybroker.props;

import lombok.Getter;
import lombok.Setter;
import org.reactivecommons.async.starter.mybroker.MyBrokerSecretFiller;
import org.reactivecommons.async.starter.props.GenericAsyncPropsDomain;
import org.springframework.beans.factory.annotation.Value;

@Getter
@Setter
public class MyBrokerAsyncPropsDomain extends GenericAsyncPropsDomain<MyBrokerAsyncProps, MyBrokerConnProps> {

    public MyBrokerAsyncPropsDomain(@Value("${spring.application.name}") String defaultAppName,
                                    MyBrokerConnProps defaultRabbitProperties,
                                    AsyncMyBrokerPropsDomainProperties configured,
                                    MyBrokerSecretFiller secretFiller) {
        super(defaultAppName, defaultRabbitProperties, configured, secretFiller, MyBrokerAsyncProps.class,
                MyBrokerConnProps.class);
    }
}