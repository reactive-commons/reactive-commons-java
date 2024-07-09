package org.reactivecommons.async.rabbit.config;

import org.reactivecommons.api.domain.DomainEventBus;
import org.reactivecommons.async.commons.config.BrokerConfig;
import org.reactivecommons.async.rabbit.RabbitDomainEventBus;
import org.reactivecommons.async.rabbit.communications.ReactiveMessageSender;
import org.reactivecommons.async.rabbit.config.props.AsyncProps;
import org.reactivecommons.async.rabbit.config.props.AsyncPropsDomain;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.reactivecommons.async.api.HandlerRegistry.DEFAULT_DOMAIN;
import static reactor.rabbitmq.ExchangeSpecification.exchange;

@Configuration
@Import(RabbitMqConfig.class)
public class EventBusConfig {

    @Bean // app connection
    public DomainEventBus domainEventBus(ConnectionManager manager, BrokerConfig config,
                                         AsyncPropsDomain asyncPropsDomain) {
        ReactiveMessageSender sender = manager.getSender(DEFAULT_DOMAIN);
        AsyncProps asyncProps = asyncPropsDomain.getProps(DEFAULT_DOMAIN);
        final String exchangeName = asyncProps.getBrokerConfigProps().getDomainEventsExchangeName();
        if (asyncProps.getCreateTopology()) {
            sender.getTopologyCreator().declare(exchange(exchangeName).durable(true).type("topic")).subscribe();
        }
        return new RabbitDomainEventBus(sender, exchangeName, config);
    }
}
