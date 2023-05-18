package org.reactivecommons.async.rabbit.config;

import org.reactivecommons.api.domain.DomainEventBus;
import org.reactivecommons.async.commons.DiscardNotifier;
import org.reactivecommons.async.commons.config.BrokerConfig;
import org.reactivecommons.async.commons.converters.json.ObjectMapperSupplier;
import org.reactivecommons.async.rabbit.RabbitDiscardNotifier;
import org.reactivecommons.async.rabbit.RabbitDomainEventBus;
import org.reactivecommons.async.rabbit.communications.ReactiveMessageSender;
import org.reactivecommons.async.rabbit.config.props.BrokerConfigProps;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static reactor.rabbitmq.ExchangeSpecification.exchange;

@Configuration
@Import(RabbitMqConfig.class)
public class EventBusConfig {

    @Bean // app connection
    public DomainEventBus domainEventBus(ConnectionManager manager, BrokerConfigProps props, BrokerConfig config,
                                         ObjectMapperSupplier objectMapperSupplier) {
        String domain = "app";
        ReactiveMessageSender sender = manager.getSender(domain);
        final String exchangeName = props.getDomainEventsExchangeName();
        sender.getTopologyCreator().declare(exchange(exchangeName).durable(true).type("topic")).subscribe();
        DomainEventBus domainEventBus = new RabbitDomainEventBus(sender, exchangeName, config);
        manager.setDiscardNotifier(domain, createDiscardNotifier(domainEventBus, objectMapperSupplier));
        return domainEventBus;
    }

    private DiscardNotifier createDiscardNotifier(DomainEventBus domainEventBus, ObjectMapperSupplier objectMapperSupplier) {
        return new RabbitDiscardNotifier(domainEventBus, objectMapperSupplier.get());
    }
}
