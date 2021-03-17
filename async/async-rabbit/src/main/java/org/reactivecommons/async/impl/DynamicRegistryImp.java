
package org.reactivecommons.async.impl;

import lombok.RequiredArgsConstructor;
import org.reactivecommons.async.api.DynamicRegistry;
import org.reactivecommons.async.api.handlers.EventHandler;
import org.reactivecommons.async.api.handlers.registered.RegisteredEventListener;
import org.reactivecommons.async.impl.communications.TopologyCreator;
import org.reactivecommons.async.commons.config.IBrokerConfigProps;
import reactor.core.publisher.Mono;
import reactor.rabbitmq.BindingSpecification;

@RequiredArgsConstructor
public class DynamicRegistryImp implements DynamicRegistry {

    private final HandlerResolver resolver;
    private final TopologyCreator topologyCreator;
    private final IBrokerConfigProps props;


    @Override
    public <T> Mono<Void> listenEvent(String eventName, EventHandler<T> fn, Class<T> eventClass) {
        resolver.addEventListener(new RegisteredEventListener<>(eventName, fn, eventClass));

        return topologyCreator.bind(buildBindingSpecification(eventName))
                .then();
    }

    @Override
    public Mono<Void> startListeningEvent(String eventName) {
        return topologyCreator.bind(buildBindingSpecification(eventName))
                .then();
    }

    @Override
    public Mono<Void> stopListeningEvent(String eventName) {
        return topologyCreator.unbind(buildBindingSpecification(eventName))
                .then();
    }

    private BindingSpecification buildBindingSpecification(String eventName) {
        return BindingSpecification.binding(props.getDomainEventsExchangeName(), eventName, props.getEventsQueue());
    }

}
