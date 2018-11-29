package org.reactivecommons.async.impl.listeners;

import com.fasterxml.jackson.databind.JsonNode;
import com.rabbitmq.client.AMQP;
import lombok.Data;
import lombok.extern.java.Log;
import org.reactivecommons.api.domain.DomainEvent;
import org.reactivecommons.async.impl.communications.Message;
import org.reactivecommons.async.impl.converters.MessageConverter;
import org.reactivecommons.async.api.handlers.registered.RegisteredEventListener;
import org.reactivecommons.async.impl.EventExecutor;
import org.reactivecommons.async.impl.HandlerResolver;
import org.reactivecommons.async.impl.communications.ReactiveMessageListener;
import org.reactivecommons.async.impl.communications.TopologyCreator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.rabbitmq.AcknowledgableDelivery;
import reactor.rabbitmq.BindingSpecification;
import reactor.rabbitmq.ExchangeSpecification;
import reactor.rabbitmq.QueueSpecification;

import java.util.function.Function;

import static reactor.core.publisher.Flux.fromIterable;

@Log
public class ApplicationEventListener extends GenericMessageListener {

    private final MessageConverter messageConverter;
    private final HandlerResolver resolver;
    private final String eventsExchange;


    public ApplicationEventListener(ReactiveMessageListener receiver, String queueName, HandlerResolver resolver, String eventsExchange, MessageConverter messageConverter) {
        super(queueName, receiver);
        this.resolver = resolver;
        this.eventsExchange = eventsExchange;
        this.messageConverter = messageConverter;
    }

    protected Mono<Void> setUpBindings(TopologyCreator creator) {
        final Flux<AMQP.Queue.BindOk> bindings = fromIterable(resolver.getEventListeners())
            .flatMap(listener -> creator.bind(BindingSpecification.binding(eventsExchange, listener.getPath(), queueName)));
        final Mono<AMQP.Exchange.DeclareOk> declareExchange = creator.declare(ExchangeSpecification.exchange(eventsExchange).durable(true).type("topic"));
        final Mono<AMQP.Queue.DeclareOk> declareQueue = creator.declare(QueueSpecification.queue(queueName).durable(true));
        return declareExchange.then(declareQueue).thenMany(bindings).then();
    }

    @Override
    protected Function<Message, Mono<Object>> rawMessageHandler(String executorPath) {
        final RegisteredEventListener<Object> handler = resolver.getEventListener(executorPath);
        final Class<Object> eventClass = handler.getInputClass();
        Function<Message, DomainEvent<Object>> converter = msj -> messageConverter.readDomainEvent(msj, eventClass);
        final EventExecutor<Object> executor = new EventExecutor<>(handler.getHandler(), converter);
        return msj -> executor.execute(msj).cast(Object.class);
    }

    protected String getExecutorPath(AcknowledgableDelivery msj) {
        return msj.getEnvelope().getRoutingKey();
    }


    @Data
    private static class DomainEventInt {
        private String name;
        private String eventId;
        private JsonNode data;
    }


}


