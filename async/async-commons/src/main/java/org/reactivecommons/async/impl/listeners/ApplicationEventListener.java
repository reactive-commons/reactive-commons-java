package org.reactivecommons.async.impl.listeners;

import com.rabbitmq.client.AMQP;
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
import org.reactivecommons.async.impl.ext.CustomErrorReporter;
import org.reactivecommons.async.impl.utils.matcher.KeyMatcher;
import org.reactivecommons.async.impl.utils.matcher.Matcher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.rabbitmq.AcknowledgableDelivery;
import reactor.rabbitmq.BindingSpecification;
import reactor.rabbitmq.ExchangeSpecification;

import java.util.Optional;
import java.util.function.Function;

import static java.lang.String.format;
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
    private final String appName;


    public ApplicationEventListener(ReactiveMessageListener receiver,
                                    String queueName,
                                    HandlerResolver resolver,
                                    String eventsExchange,
                                    MessageConverter messageConverter,
                                    boolean withDLQRetry,
                                    long maxRetries, int retryDelay,
                                    Optional<Integer> maxLengthBytes,
                                    DiscardNotifier discardNotifier,
                                    CustomErrorReporter errorReporter,
                                    String appName) {
        super(queueName, receiver, withDLQRetry, maxRetries, discardNotifier, "event", errorReporter);
        this.retryDelay = retryDelay;
        this.withDLQRetry = withDLQRetry;
        this.resolver = resolver;
        this.eventsExchange = eventsExchange;
        this.messageConverter = messageConverter;
        this.maxLengthBytes = maxLengthBytes;
        this.keyMatcher = new KeyMatcher();
        this.appName = appName;
    }

    protected Mono<Void> setUpBindings(TopologyCreator creator) {
        if (withDLQRetry) {
            final String eventsDLQExchangeName = format("%s.%s.DLQ", appName, eventsExchange);
            final String retryExchangeName = format("%s.%s", appName, eventsExchange);
            final Mono<AMQP.Exchange.DeclareOk> declareExchange = creator.declare(ExchangeSpecification.exchange(eventsExchange).durable(true).type("topic"));
            final Mono<AMQP.Exchange.DeclareOk> retryExchange = creator.declare(ExchangeSpecification.exchange(retryExchangeName).durable(true).type("topic"));
            final Mono<AMQP.Exchange.DeclareOk> declareExchangeDLQ = creator.declare(ExchangeSpecification.exchange(eventsDLQExchangeName).durable(true).type("topic"));
            final Mono<AMQP.Queue.DeclareOk> declareDLQ = creator.declareDLQ(queueName, retryExchangeName, retryDelay, maxLengthBytes);
            final Mono<AMQP.Queue.DeclareOk> declareQueue = creator.declareQueue(queueName, eventsDLQExchangeName, maxLengthBytes);
            final Flux<AMQP.Queue.BindOk> bindings = fromIterable(resolver.getEventListeners()).flatMap(listener -> creator.bind(BindingSpecification.binding(eventsExchange, listener.getPath(), queueName)));
            final Mono<AMQP.Queue.BindOk> bindingDLQ = creator.bind(BindingSpecification.binding(eventsDLQExchangeName, "#", queueName + ".DLQ"));
            final Mono<AMQP.Queue.BindOk> retryBinding = creator.bind(BindingSpecification.binding(retryExchangeName, "#", queueName));
            return declareExchange.then(retryExchange).then(declareExchangeDLQ).then(declareQueue).then(declareDLQ).thenMany(bindings).then(bindingDLQ).then(retryBinding).then();
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
        final String matchedKey = keyMatcher.match(resolver.getToListenEventNames(), executorPath);
        final RegisteredEventListener<Object> handler = getEventListener(matchedKey);

        final Class<Object> eventClass = handler.getInputClass();
        Function<Message, DomainEvent<Object>> converter = msj -> messageConverter.readDomainEvent(msj, eventClass);

        final EventExecutor<Object> executor = new EventExecutor<>(handler.getHandler(), converter);

        return msj -> executor
                .execute(msj)
                .cast(Object.class);
    }

    private RegisteredEventListener<Object> getEventListener(String matchedKey) {
        RegisteredEventListener<Object> eventListener = resolver.getEventListener(matchedKey);

        if (eventListener == null) {
            return resolver.getDynamicEventsHandler(matchedKey);
        }

        return eventListener;
    }

    protected String getExecutorPath(AcknowledgableDelivery msj) {
        return msj.getEnvelope().getRoutingKey();
    }

    @Override
    protected Object parseMessageForReporter(Message msj) {
        return messageConverter.readDomainEventStructure(msj);
    }

}


