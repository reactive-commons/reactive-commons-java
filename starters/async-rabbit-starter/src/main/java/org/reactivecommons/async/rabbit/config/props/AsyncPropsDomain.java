package org.reactivecommons.async.rabbit.config.props;

import lombok.Getter;
import lombok.Setter;
import org.reactivecommons.async.rabbit.config.RabbitProperties;
import org.reactivecommons.async.starter.props.GenericAsyncPropsDomain;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import java.lang.reflect.Constructor;

@Getter
@Setter
public class AsyncPropsDomain extends GenericAsyncPropsDomain<AsyncProps, RabbitProperties> {

    @Autowired
    public AsyncPropsDomain(@Value("${spring.application.name}") String defaultAppName,
                            RabbitProperties defaultRabbitProperties,
                            AsyncRabbitPropsDomainProperties configured,
                            RabbitSecretFiller secretFiller,
                            ObjectProvider<RabbitPropsCustomizer> customizer) {
        super(defaultAppName, defaultRabbitProperties,
                applyCustomizer(configured, customizer.getIfAvailable()),
                secretFiller, AsyncProps.class, RabbitProperties.class);
    }

    public AsyncPropsDomain(String defaultAppName,
                            RabbitProperties defaultRabbitProperties,
                            AsyncRabbitPropsDomainProperties configured,
                            RabbitSecretFiller secretFiller) {
        super(defaultAppName, defaultRabbitProperties, configured, secretFiller, AsyncProps.class,
                RabbitProperties.class);
    }

    @SuppressWarnings("unchecked")
    public static AsyncPropsDomainBuilder<AsyncProps, RabbitProperties, AsyncRabbitPropsDomainProperties,
            AsyncPropsDomain> builder() {
        try {
            Constructor<AsyncPropsDomain> ctor = AsyncPropsDomain.class.getDeclaredConstructor(
                    String.class, RabbitProperties.class, AsyncRabbitPropsDomainProperties.class,
                    RabbitSecretFiller.class);
            return GenericAsyncPropsDomain.builder(RabbitProperties.class,
                    AsyncRabbitPropsDomainProperties.class, ctor);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("Constructor not found", e);
        }
    }

    @Override
    protected void fillCustoms(AsyncProps asyncProps) {
        if (asyncProps.getBrokerConfigProps() == null) {
            asyncProps.setBrokerConfigProps(new BrokerConfigProps(asyncProps));
        }
    }

    private static AsyncRabbitPropsDomainProperties applyCustomizer(
            AsyncRabbitPropsDomainProperties configured, RabbitPropsCustomizer customizer) {
        if (customizer != null) {
            customizer.customize(configured);
        }
        return configured;
    }

    public interface RabbitSecretFiller extends SecretFiller<RabbitProperties> {
    }

    /**
     * Customizer interface for RabbitMQ async properties.
     * Allows programmatic modification of properties loaded from YAML configuration.
     * Properties modified through this customizer take precedence over YAML values.
     */
    @FunctionalInterface
    public interface RabbitPropsCustomizer {
        void customize(AsyncRabbitPropsDomainProperties domainProperties);
    }

}
