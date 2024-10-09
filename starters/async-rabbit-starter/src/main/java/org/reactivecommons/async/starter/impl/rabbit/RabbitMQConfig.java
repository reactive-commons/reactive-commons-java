package org.reactivecommons.async.starter.impl.rabbit;

import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import org.reactivecommons.async.api.DynamicRegistry;
import org.reactivecommons.async.commons.config.IBrokerConfigProps;
import org.reactivecommons.async.commons.converters.json.ObjectMapperSupplier;
import org.reactivecommons.async.rabbit.DynamicRegistryImp;
import org.reactivecommons.async.rabbit.RabbitMQBrokerProviderFactory;
import org.reactivecommons.async.rabbit.RabbitMQSetupUtils;
import org.reactivecommons.async.rabbit.communications.TopologyCreator;
import org.reactivecommons.async.rabbit.config.RabbitProperties;
import org.reactivecommons.async.rabbit.config.RabbitPropertiesAutoConfig;
import org.reactivecommons.async.rabbit.config.props.AsyncProps;
import org.reactivecommons.async.rabbit.config.props.AsyncPropsDomain;
import org.reactivecommons.async.rabbit.config.props.AsyncRabbitPropsDomainProperties;
import org.reactivecommons.async.rabbit.config.props.BrokerConfigProps;
import org.reactivecommons.async.rabbit.converters.json.RabbitJacksonMessageConverter;
import org.reactivecommons.async.starter.config.DomainHandlers;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.reactivecommons.async.api.HandlerRegistry.DEFAULT_DOMAIN;

@Log
@Configuration
@RequiredArgsConstructor
@EnableConfigurationProperties({RabbitPropertiesAutoConfig.class, AsyncRabbitPropsDomainProperties.class})
@Import({AsyncPropsDomain.class, RabbitMQBrokerProviderFactory.class})
public class RabbitMQConfig {

    @Bean
    @ConditionalOnMissingBean(RabbitJacksonMessageConverter.class)
    public RabbitJacksonMessageConverter messageConverter(ObjectMapperSupplier objectMapperSupplier) {
        return new RabbitJacksonMessageConverter(objectMapperSupplier.get());
    }

    @Bean
    @ConditionalOnMissingBean(DynamicRegistry.class)
    public DynamicRegistry dynamicRegistry(AsyncPropsDomain asyncPropsDomain, DomainHandlers handlers) {
        AsyncProps props = asyncPropsDomain.getProps(DEFAULT_DOMAIN);
        TopologyCreator topologyCreator = RabbitMQSetupUtils.createTopologyCreator(props);
        IBrokerConfigProps brokerConfigProps = new BrokerConfigProps(asyncPropsDomain.getProps(DEFAULT_DOMAIN));
        return new DynamicRegistryImp(handlers.get(DEFAULT_DOMAIN), topologyCreator, brokerConfigProps);
    }

    @Bean
    @ConditionalOnMissingBean(AsyncPropsDomain.RabbitSecretFiller.class)
    public AsyncPropsDomain.RabbitSecretFiller defaultRabbitSecretFiller() {
        return (ignoredDomain, ignoredProps) -> {
        };
    }

    @Bean
    @ConditionalOnMissingBean(RabbitProperties.class)
    public RabbitProperties defaultRabbitProperties(RabbitPropertiesAutoConfig properties, ObjectMapperSupplier supplier) {
        return supplier.get().convertValue(properties, RabbitProperties.class);
    }

}
