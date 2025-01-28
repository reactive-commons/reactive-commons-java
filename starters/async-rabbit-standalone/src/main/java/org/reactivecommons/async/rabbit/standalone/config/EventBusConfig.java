package org.reactivecommons.async.rabbit.standalone.config;

import lombok.RequiredArgsConstructor;
import org.reactivecommons.api.domain.DomainEventBus;
import org.reactivecommons.async.rabbit.RabbitDomainEventBus;
import org.reactivecommons.async.rabbit.communications.ReactiveMessageSender;

import static reactor.rabbitmq.ExchangeSpecification.exchange;


@RequiredArgsConstructor
public class EventBusConfig {

    private final String domainEventsExchangeName;

    public DomainEventBus domainEventBus(ReactiveMessageSender sender) {
        sender.getTopologyCreator().declare(exchange(domainEventsExchangeName).durable(true).type("topic")).subscribe();
        return new RabbitDomainEventBus(sender, domainEventsExchangeName);
    }
}
