package org.reactivecommons.async.starter.mybroker;

import lombok.AllArgsConstructor;
import org.reactivecommons.api.domain.DomainEventBus;
import org.reactivecommons.async.api.DirectAsyncGateway;
import org.reactivecommons.async.commons.HandlerResolver;
import org.reactivecommons.async.starter.broker.BrokerProvider;
import org.reactivecommons.async.starter.broker.DiscardProvider;
import org.reactivecommons.async.starter.mybroker.props.MyBrokerAsyncProps;
import org.springframework.boot.actuate.health.Health;
import reactor.core.publisher.Mono;

@AllArgsConstructor
public class MyBrokerProvider implements BrokerProvider<MyBrokerAsyncProps> {
    private final String domain;
    private final MyBrokerAsyncProps props;
    private final DiscardProvider discardProvider;

    @Override
    public MyBrokerAsyncProps getProps() {
        return null;
    }

    @Override
    public DomainEventBus getDomainBus() {
        return null;
    }

    @Override
    public DirectAsyncGateway getDirectAsyncGateway(HandlerResolver resolver) {
        return null;
    }

    @Override
    public void listenDomainEvents(HandlerResolver resolver) {

    }

    @Override
    public void listenNotificationEvents(HandlerResolver resolver) {

    }

    @Override
    public void listenCommands(HandlerResolver resolver) {

    }

    @Override
    public void listenQueries(HandlerResolver resolver) {

    }

    @Override
    public void listenReplies(HandlerResolver resolver) {

    }

    @Override
    public Mono<Health> healthCheck() {
        return null;
    }
}