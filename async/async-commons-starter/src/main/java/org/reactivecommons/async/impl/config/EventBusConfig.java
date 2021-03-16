package org.reactivecommons.async.impl.config;

import org.reactivecommons.api.domain.DomainEventBus;
import org.reactivecommons.async.impl.RabbitDomainEventBus;
import org.reactivecommons.async.impl.communications.ReactiveMessageSender;
import org.reactivecommons.async.impl.config.props.BrokerConfigProps;
import org.reactivecommons.async.parent.config.BrokerConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static reactor.rabbitmq.ExchangeSpecification.exchange;

@Configuration
@Import(RabbitMqConfig.class)
public class EventBusConfig {

    @Bean
    public DomainEventBus domainEventBus(ReactiveMessageSender sender, BrokerConfigProps props, BrokerConfig config) {
        final String exchangeName = props.getDomainEventsExchangeName();
        sender.getTopologyCreator().declare(exchange(exchangeName).durable(true).type("topic")).subscribe();
        return new RabbitDomainEventBus(sender, exchangeName, config);
    }
}
