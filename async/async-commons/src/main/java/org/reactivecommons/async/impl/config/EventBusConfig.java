package org.reactivecommons.async.impl.config;

import org.reactivecommons.api.domain.DomainEventBus;
import org.reactivecommons.async.impl.RabbitDomainEventBus;
import org.reactivecommons.async.impl.communications.ReactiveMessageSender;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static reactor.rabbitmq.ExchangeSpecification.exchange;

@Configuration
@Import(RabbitMqConfig.class)
public class EventBusConfig {

    @Value("${app.async.domain.events.exchange:domainEvents}")
    private String domainEventsExchangeName;

    @Bean
    public DomainEventBus domainEventBus(ReactiveMessageSender sender) {
        sender.getTopologyCreator().declare(exchange(domainEventsExchangeName).durable(true).type("topic")).subscribe();
        return new RabbitDomainEventBus(sender, domainEventsExchangeName);
    }
}
