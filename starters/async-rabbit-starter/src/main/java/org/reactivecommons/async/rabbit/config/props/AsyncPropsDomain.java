package org.reactivecommons.async.rabbit.config.props;

import lombok.Getter;
import lombok.Setter;
import org.reactivecommons.async.rabbit.config.RabbitProperties;
import org.reactivecommons.async.starter.exceptions.InvalidConfigurationException;
import org.reactivecommons.async.starter.props.GenericAsyncPropsDomain;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import java.lang.reflect.Constructor;

import static org.reactivecommons.async.api.HandlerRegistry.DEFAULT_DOMAIN;

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
            if (!configured.containsKey(DEFAULT_DOMAIN)) {
                throw new InvalidConfigurationException("""
                        RabbitPropsCustomizer was applied but the default domain 'app' is not defined. \
                        When using RabbitPropsCustomizer, you must declare the 'app' domain in your \
                        application.yaml (app.async.app.*), or add the 'app' domain (or any other new domain) \
                        directly inside the customizer using \
                        domainProperties.put("app", AsyncProps.builder()...build()).""");
            }
        }
        return configured;
    }

    @Deprecated(forRemoval = true, since = "7.2.0")
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
