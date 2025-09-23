package org.reactivecommons.async.starter.impl.listener.rabbit;

import org.reactivecommons.async.api.DynamicRegistry;
import org.reactivecommons.async.commons.config.IBrokerConfigProps;
import org.reactivecommons.async.rabbit.ConnectionFactoryCustomizer;
import org.reactivecommons.async.rabbit.DynamicRegistryImp;
import org.reactivecommons.async.rabbit.RabbitMQSetupUtils;
import org.reactivecommons.async.rabbit.communications.TopologyCreator;
import org.reactivecommons.async.rabbit.config.props.AsyncProps;
import org.reactivecommons.async.rabbit.config.props.AsyncPropsDomain;
import org.reactivecommons.async.rabbit.config.props.BrokerConfigProps;
import org.reactivecommons.async.starter.config.DomainHandlers;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.reactivecommons.async.api.HandlerRegistry.DEFAULT_DOMAIN;

@Configuration
public class RabbitMQListenerOnlyConfig {

    @Bean
    @ConditionalOnMissingBean(DynamicRegistry.class)
    public DynamicRegistry dynamicRegistry(AsyncPropsDomain asyncPropsDomain, DomainHandlers handlers, ConnectionFactoryCustomizer cfCustomizer) {
        AsyncProps props = asyncPropsDomain.getProps(DEFAULT_DOMAIN);
        TopologyCreator topologyCreator = RabbitMQSetupUtils.createTopologyCreator(props, cfCustomizer);
        IBrokerConfigProps brokerConfigProps = new BrokerConfigProps(asyncPropsDomain.getProps(DEFAULT_DOMAIN));
        return new DynamicRegistryImp(handlers.get(DEFAULT_DOMAIN), topologyCreator, brokerConfigProps);
    }
}
