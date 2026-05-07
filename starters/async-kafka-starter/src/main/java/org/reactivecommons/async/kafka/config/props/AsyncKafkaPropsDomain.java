package org.reactivecommons.async.kafka.config.props;

import lombok.Getter;
import lombok.Setter;
import org.reactivecommons.async.kafka.config.KafkaProperties;
import org.reactivecommons.async.starter.props.GenericAsyncPropsDomain;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import java.lang.reflect.Constructor;

@Getter
@Setter
public class AsyncKafkaPropsDomain extends GenericAsyncPropsDomain<AsyncKafkaProps, KafkaProperties> {

    @Autowired
    public AsyncKafkaPropsDomain(@Value("${spring.application.name}") String defaultAppName,
                                 KafkaProperties defaultKafkaProperties,
                                 AsyncKafkaPropsDomainProperties configured,
                                 KafkaSecretFiller kafkaSecretFiller,
                                 ObjectProvider<KafkaPropsCustomizer> customizer) {
        super(defaultAppName, defaultKafkaProperties,
                applyCustomizer(configured, customizer.getIfAvailable()),
                kafkaSecretFiller, AsyncKafkaProps.class, KafkaProperties.class);
    }

    public AsyncKafkaPropsDomain(String defaultAppName,
                                 KafkaProperties defaultKafkaProperties,
                                 AsyncKafkaPropsDomainProperties configured,
                                 KafkaSecretFiller kafkaSecretFiller) {
        super(defaultAppName, defaultKafkaProperties, configured, kafkaSecretFiller, AsyncKafkaProps.class,
                KafkaProperties.class);
    }

    @SuppressWarnings("unchecked")
    public static AsyncPropsDomainBuilder<AsyncKafkaProps, KafkaProperties, AsyncKafkaPropsDomainProperties,
            AsyncKafkaPropsDomain> builder() {
        try {
            Constructor<AsyncKafkaPropsDomain> ctor = AsyncKafkaPropsDomain.class.getDeclaredConstructor(
                    String.class, KafkaProperties.class, AsyncKafkaPropsDomainProperties.class,
                    KafkaSecretFiller.class);
            return GenericAsyncPropsDomain.builder(KafkaProperties.class,
                    AsyncKafkaPropsDomainProperties.class, ctor);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("Constructor not found", e);
        }
    }

    private static AsyncKafkaPropsDomainProperties applyCustomizer(
            AsyncKafkaPropsDomainProperties configured, KafkaPropsCustomizer customizer) {
        if (customizer != null) {
            customizer.customize(configured);
        }
        return configured;
    }

    public interface KafkaSecretFiller extends GenericAsyncPropsDomain.SecretFiller<KafkaProperties> {
    }

    /**
     * Customizer interface for Kafka async properties.
     * Allows programmatic modification of properties loaded from YAML configuration.
     * Properties modified through this customizer take precedence over YAML values.
     */
    @FunctionalInterface
    public interface KafkaPropsCustomizer {
        void customize(AsyncKafkaPropsDomainProperties domainProperties);
    }

}
