package org.reactivecommons.async.rabbit.config;

import org.reactivecommons.api.domain.DomainEventBus;
import org.reactivecommons.async.rabbit.RabbitDomainEventBus;
import org.reactivecommons.async.rabbit.communications.ReactiveMessageSender;

import static reactor.rabbitmq.ExchangeSpecification.exchange;

public class EventBusConfig {

    private String domainEventsExchangeName;

    public EventBusConfig(String domainEventsExchangeName) {
        this.domainEventsExchangeName = domainEventsExchangeName;
    }

    public DomainEventBus domainEventBus(ReactiveMessageSender sender) {
        sender.getTopologyCreator().declare(exchange(domainEventsExchangeName).durable(true).type("topic")).subscribe();
        return new RabbitDomainEventBus(sender, domainEventsExchangeName);
    }
}
