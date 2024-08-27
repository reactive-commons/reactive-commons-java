package org.reactivecommons.async.kafka.config.props;

import lombok.Getter;
import lombok.Setter;
import org.reactivecommons.async.kafka.config.KafkaProperties;
import org.reactivecommons.async.starter.GenericAsyncPropsDomain;
import org.springframework.beans.factory.annotation.Value;

import java.lang.reflect.Constructor;

@Getter
@Setter
public class AsyncKafkaPropsDomain extends GenericAsyncPropsDomain<AsyncKafkaProps, KafkaProperties> {

    public AsyncKafkaPropsDomain(@Value("${spring.application.name}") String defaultAppName,
                                 KafkaProperties defaultKafkaProperties,
                                 AsyncKafkaPropsDomainProperties configured,
                                 KafkaSecretFiller kafkaSecretFiller) {
        super(defaultAppName, defaultKafkaProperties, configured, kafkaSecretFiller, AsyncKafkaProps.class,
                KafkaProperties.class);
    }

    @SuppressWarnings("unchecked")
    public static AsyncPropsDomainBuilder<AsyncKafkaProps, KafkaProperties, AsyncKafkaPropsDomainProperties,
            AsyncKafkaPropsDomain> builder() {
        return GenericAsyncPropsDomain.builder(AsyncKafkaProps.class,
                KafkaProperties.class,
                AsyncKafkaPropsDomainProperties.class,
                (Constructor<AsyncKafkaPropsDomain>) AsyncKafkaPropsDomain.class.getDeclaredConstructors()[0]);
    }

    public interface KafkaSecretFiller extends GenericAsyncPropsDomain.SecretFiller<KafkaProperties> {
    }

}
