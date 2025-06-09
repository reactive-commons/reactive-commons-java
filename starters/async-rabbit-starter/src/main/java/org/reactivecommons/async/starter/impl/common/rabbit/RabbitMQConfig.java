package org.reactivecommons.async.starter.impl.common.rabbit;

import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import org.reactivecommons.async.commons.converters.json.ObjectMapperSupplier;
import org.reactivecommons.async.rabbit.RabbitMQBrokerProviderFactory;
import org.reactivecommons.async.rabbit.config.RabbitProperties;
import org.reactivecommons.async.rabbit.config.RabbitPropertiesAutoConfig;
import org.reactivecommons.async.rabbit.config.props.AsyncPropsDomain;
import org.reactivecommons.async.rabbit.config.props.AsyncRabbitPropsDomainProperties;
import org.reactivecommons.async.rabbit.converters.json.RabbitJacksonMessageConverter;
import org.reactivecommons.async.rabbit.discard.RabbitMQDiscardProviderConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Log
@Configuration
@RequiredArgsConstructor
@EnableConfigurationProperties({RabbitPropertiesAutoConfig.class, AsyncRabbitPropsDomainProperties.class})
@Import({AsyncPropsDomain.class, RabbitMQBrokerProviderFactory.class, RabbitMQDiscardProviderConfig.class})
public class RabbitMQConfig {

    @Bean
    @ConditionalOnMissingBean(RabbitJacksonMessageConverter.class)
    public RabbitJacksonMessageConverter messageConverter(ObjectMapperSupplier objectMapperSupplier) {
        return new RabbitJacksonMessageConverter(objectMapperSupplier.get());
    }

    @Bean
    @ConditionalOnMissingBean(AsyncPropsDomain.RabbitSecretFiller.class)
    public AsyncPropsDomain.RabbitSecretFiller defaultRabbitSecretFiller() {
        return (ignoredDomain, ignoredProps) -> {
        };
    }

    @Bean
    @ConditionalOnMissingBean(RabbitProperties.class)
    public RabbitProperties defaultRabbitProperties(RabbitPropertiesAutoConfig properties,
                                                    ObjectMapperSupplier supplier) {
        return supplier.get().convertValue(properties, RabbitProperties.class);
    }

}
