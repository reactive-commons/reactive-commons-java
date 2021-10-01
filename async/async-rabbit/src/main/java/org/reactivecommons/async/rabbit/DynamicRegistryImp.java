
package org.reactivecommons.async.rabbit;

import lombok.RequiredArgsConstructor;
import org.reactivecommons.async.api.DynamicRegistry;
import org.reactivecommons.async.api.handlers.EventHandler;
import org.reactivecommons.async.api.handlers.QueryHandler;
import org.reactivecommons.async.api.handlers.QueryHandlerDelegate;
import org.reactivecommons.async.api.handlers.registered.RegisteredEventListener;
import org.reactivecommons.async.api.handlers.registered.RegisteredQueryHandler;
import org.reactivecommons.async.commons.config.IBrokerConfigProps;
import org.reactivecommons.async.rabbit.communications.TopologyCreator;
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
    public <T, R> void serveQuery(String resource, QueryHandler<T, R> handler, Class<R> queryClass) {
        resolver.addQueryHandler(new RegisteredQueryHandler<>(resource, (ignored, message) -> handler.handle(message), queryClass));
    }

    @Override
    public <R> void serveQuery(String resource, QueryHandlerDelegate<Void, R> handler, Class<R> queryClass) {
        resolver.addQueryHandler(new RegisteredQueryHandler<>(resource, handler, queryClass));
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
