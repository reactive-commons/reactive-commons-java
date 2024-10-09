package org.reactivecommons.async.rabbit.config.props;

import lombok.Getter;
import lombok.Setter;
import org.reactivecommons.async.rabbit.config.RabbitProperties;
import org.reactivecommons.async.starter.props.GenericAsyncPropsDomain;
import org.springframework.beans.factory.annotation.Value;

import java.lang.reflect.Constructor;

@Getter
@Setter
public class AsyncPropsDomain extends GenericAsyncPropsDomain<AsyncProps, RabbitProperties> {
    public AsyncPropsDomain(@Value("${spring.application.name}") String defaultAppName,
                            RabbitProperties defaultRabbitProperties,
                            AsyncRabbitPropsDomainProperties configured,
                            RabbitSecretFiller secretFiller) {
        super(defaultAppName, defaultRabbitProperties, configured, secretFiller, AsyncProps.class,
                RabbitProperties.class);
    }

    @SuppressWarnings("unchecked")
    public static AsyncPropsDomainBuilder<AsyncProps, RabbitProperties, AsyncRabbitPropsDomainProperties,
            AsyncPropsDomain> builder() {
        return GenericAsyncPropsDomain.builder(AsyncProps.class,
                RabbitProperties.class,
                AsyncRabbitPropsDomainProperties.class,
                (Constructor<AsyncPropsDomain>) AsyncPropsDomain.class.getDeclaredConstructors()[0]);
    }

    @Override
    protected void fillCustoms(AsyncProps asyncProps) {
        if (asyncProps.getBrokerConfigProps() == null) {
            asyncProps.setBrokerConfigProps(new BrokerConfigProps(asyncProps));
        }
    }

    public interface RabbitSecretFiller extends SecretFiller<RabbitProperties> {
    }

}
