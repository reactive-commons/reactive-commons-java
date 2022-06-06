package org.reactivecommons.async.impl.config;

import lombok.RequiredArgsConstructor;
import org.reactivecommons.api.domain.DomainEventBus;
import org.reactivecommons.async.impl.SNSDomainEventBus;
import org.reactivecommons.async.impl.config.props.BrokerConfigProps;
import org.reactivecommons.async.impl.sns.communications.Sender;
import org.reactivecommons.async.impl.sns.communications.TopologyCreator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import javax.annotation.PostConstruct;

import static reactor.rabbitmq.ExchangeSpecification.exchange;

@Configuration
@Import(AWSConfig.class)
@RequiredArgsConstructor
public class EventBusConfig {

    private final BrokerConfigProps props;
    private final TopologyCreator topology;

    @PostConstruct
    public void createEventTopic() {
        final String exchangeName = props.getDomainEventsExchangeName();
        topology.createTopic(exchangeName).block();
    }

    @Bean
    public DomainEventBus domainEventBus(Sender sender) {
        final String exchangeName = props.getDomainEventsExchangeName();
        return new SNSDomainEventBus(sender, exchangeName);
    }
}
