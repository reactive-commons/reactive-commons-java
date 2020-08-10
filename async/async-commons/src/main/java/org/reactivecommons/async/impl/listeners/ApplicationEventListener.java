package org.reactivecommons.async.impl.listeners;

import com.fasterxml.jackson.databind.JsonNode;
import com.rabbitmq.client.AMQP;
import lombok.Data;
import lombok.extern.java.Log;
import org.reactivecommons.api.domain.DomainEvent;
import org.reactivecommons.async.impl.DiscardNotifier;
import org.reactivecommons.async.impl.communications.Message;
import org.reactivecommons.async.impl.converters.MessageConverter;
import org.reactivecommons.async.api.handlers.registered.RegisteredEventListener;
import org.reactivecommons.async.impl.EventExecutor;
import org.reactivecommons.async.impl.HandlerResolver;
import org.reactivecommons.async.impl.communications.ReactiveMessageListener;
import org.reactivecommons.async.impl.communications.TopologyCreator;
import org.reactivecommons.async.impl.utils.matcher.KeyMatcher;
import org.reactivecommons.async.impl.utils.matcher.Matcher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.rabbitmq.AcknowledgableDelivery;
import reactor.rabbitmq.BindingSpecification;
import reactor.rabbitmq.ExchangeSpecification;
import reactor.rabbitmq.QueueSpecification;

import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static reactor.core.publisher.Flux.fromIterable;

@Log
public class ApplicationEventListener extends GenericMessageListener {

    private final MessageConverter messageConverter;
    private final HandlerResolver resolver;
    private final String eventsExchange;
    private final boolean withDLQRetry;
    private final int retryDelay;
    private final Optional<Integer> maxLengthBytes;
    private final Matcher keyMatcher;


    

    public ApplicationEventListener(ReactiveMessageListener receiver, 
                                    String queueName, 
                                    HandlerResolver resolver, 
                                    String eventsExchange, 
                                    MessageConverter messageConverter, 
                                    boolean withDLQRetry, 
                                    long maxRetries, int retryDelay, 
                                    Optional<Integer> maxLengthBytes, 
                                    DiscardNotifier discardNotifier) {
        super(queueName, receiver, withDLQRetry, maxRetries, discardNotifier, "event");
        this.retryDelay = retryDelay;
        this.withDLQRetry = withDLQRetry;
        this.resolver = resolver;
        this.eventsExchange = eventsExchange;
        this.messageConverter = messageConverter;
        this.maxLengthBytes = maxLengthBytes;
        this.keyMatcher = new KeyMatcher();
    }

    protected Mono<Void> setUpBindings(TopologyCreator creator) {
        if (withDLQRetry) {
            final Mono<AMQP.Exchange.DeclareOk> declareExchange = creator.declare(ExchangeSpecification.exchange(eventsExchange).durable(true).type("topic"));
            final Mono<AMQP.Exchange.DeclareOk> declareExchangeDLQ = creator.declare(ExchangeSpecification.exchange(eventsExchange+".DLQ").durable(true).type("topic"));
            final Mono<AMQP.Queue.DeclareOk> declareDLQ = creator.declareDLQ(queueName, eventsExchange, retryDelay, maxLengthBytes);
            final Mono<AMQP.Queue.DeclareOk> declareQueue = creator.declareQueue(queueName, eventsExchange+".DLQ", maxLengthBytes);
            final Flux<AMQP.Queue.BindOk> bindings = fromIterable(resolver.getEventListeners()).flatMap(listener -> creator.bind(BindingSpecification.binding(eventsExchange, listener.getPath(), queueName)));
            final Flux<AMQP.Queue.BindOk> bindingDLQ = fromIterable(resolver.getEventListeners()).flatMap(listener -> creator.bind(BindingSpecification.binding(eventsExchange+".DLQ", listener.getPath(), queueName + ".DLQ")));
            return declareExchange.then(declareExchangeDLQ).then(declareQueue).then(declareDLQ).thenMany(bindings).thenMany(bindingDLQ).then();
        } else {
            final Flux<AMQP.Queue.BindOk> bindings = fromIterable(resolver.getEventListeners())
                    .flatMap(listener -> creator.bind(BindingSpecification.binding(eventsExchange, listener.getPath(), queueName)));
            final Mono<AMQP.Exchange.DeclareOk> declareExchange = creator.declare(ExchangeSpecification.exchange(eventsExchange).durable(true).type("topic"));
            final Mono<AMQP.Queue.DeclareOk> declareQueue = creator.declareQueue(queueName, maxLengthBytes);
            return declareExchange.then(declareQueue).thenMany(bindings).then();
        }

    }

    @Override
    protected Function<Message, Mono<Object>> rawMessageHandler(String executorPath) {
        final Set<String> listenerKeys = resolver.getEventListeners()
                .stream()
                .map(RegisteredEventListener::getPath)
                .collect(Collectors.toSet());
        final String matchedKey = keyMatcher.match(listenerKeys, executorPath);
        final RegisteredEventListener<Object> handler = resolver.getEventListener(matchedKey);
        final Class<Object> eventClass = handler.getInputClass();
        Function<Message, DomainEvent<Object>> converter = msj -> messageConverter.readDomainEvent(msj, eventClass);
        final EventExecutor<Object> executor = new EventExecutor<>(handler.getHandler(), converter);
        return msj -> executor
                .execute(msj)
                .cast(Object.class);
    }

    protected String getExecutorPath(AcknowledgableDelivery msj) {
        return msj.getEnvelope().getRoutingKey();
    }

}


