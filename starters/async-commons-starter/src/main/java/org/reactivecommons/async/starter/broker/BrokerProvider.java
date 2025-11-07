package org.reactivecommons.async.starter.broker;

import org.reactivecommons.api.domain.DomainEventBus;
import org.reactivecommons.async.api.DirectAsyncGateway;
import org.reactivecommons.async.commons.HandlerResolver;
import org.reactivecommons.async.starter.config.health.RCHealth;
import org.reactivecommons.async.starter.props.GenericAsyncProps;
import reactor.core.publisher.Mono;

@SuppressWarnings("rawtypes")
public interface BrokerProvider<T extends GenericAsyncProps> {
    T props();

    DomainEventBus getDomainBus();

    DirectAsyncGateway getDirectAsyncGateway();

    void listenDomainEvents(HandlerResolver resolver);

    void listenNotificationEvents(HandlerResolver resolver);

    void listenCommands(HandlerResolver resolver);

    void listenQueries(HandlerResolver resolver);

    void listenQueues(HandlerResolver resolver);

    void listenReplies();

    Mono<RCHealth> healthCheck();
}
