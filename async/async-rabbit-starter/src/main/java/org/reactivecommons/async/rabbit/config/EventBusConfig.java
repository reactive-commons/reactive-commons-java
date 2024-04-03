package org.reactivecommons.async.rabbit.config;

import org.reactivecommons.api.domain.DomainEventBus;
import org.reactivecommons.async.commons.config.BrokerConfig;
import org.reactivecommons.async.commons.config.IBrokerConfigProps;
import org.reactivecommons.async.rabbit.RabbitDomainEventBus;
import org.reactivecommons.async.rabbit.communications.ReactiveMessageSender;
import org.reactivecommons.async.rabbit.config.props.AsyncProps;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static reactor.rabbitmq.ExchangeSpecification.exchange;

@Configuration
@Import(RabbitMqConfig.class)
public class EventBusConfig {

    @Bean
    public DomainEventBus domainEventBus(ReactiveMessageSender sender, IBrokerConfigProps props, AsyncProps asyncProps,
                                         BrokerConfig config) {
        final String exchangeName = props.getDomainEventsExchangeName();
        if (asyncProps.getCreateTopology()) {
            sender.getTopologyCreator().declare(exchange(exchangeName).durable(true).type("topic")).subscribe();
        }
        return new RabbitDomainEventBus(sender, exchangeName, config);
    }
}
