package org.reactivecommons.async.rabbit.listeners;

import com.rabbitmq.client.AMQP;
import lombok.extern.java.Log;
import org.reactivecommons.async.api.handlers.CloudEventHandler;
import org.reactivecommons.async.api.handlers.DomainEventHandler;
import org.reactivecommons.async.api.handlers.registered.RegisteredEventListener;
import org.reactivecommons.async.commons.DiscardNotifier;
import org.reactivecommons.async.commons.EventExecutor;
import org.reactivecommons.async.commons.communications.Message;
import org.reactivecommons.async.commons.converters.MessageConverter;
import org.reactivecommons.async.commons.ext.CustomReporter;
import org.reactivecommons.async.rabbit.HandlerResolver;
import org.reactivecommons.async.rabbit.communications.ReactiveMessageListener;
import org.reactivecommons.async.rabbit.communications.TopologyCreator;
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
    private final String appName;


    public ApplicationEventListener(ReactiveMessageListener receiver,
                                    String queueName,
                                    String eventsExchange,
                                    HandlerResolver resolver,
                                    MessageConverter messageConverter,
                                    boolean withDLQRetry,
                                    boolean createTopology,
                                    long maxRetries, int retryDelay,
                                    Optional<Integer> maxLengthBytes,
                                    DiscardNotifier discardNotifier,
                                    CustomReporter errorReporter,
                                    String appName) {
        super(queueName, receiver, withDLQRetry, createTopology, maxRetries, retryDelay, discardNotifier,
                "event", errorReporter);
        this.retryDelay = retryDelay;
        this.withDLQRetry = withDLQRetry;
        this.resolver = resolver;
        this.eventsExchange = eventsExchange;
        this.messageConverter = messageConverter;
        this.maxLengthBytes = maxLengthBytes;
        this.appName = appName;
    }

    protected Mono<Void> setUpBindings(TopologyCreator creator) {
        final Mono<AMQP.Exchange.DeclareOk> declareExchange = creator.declare(ExchangeSpecification.exchange(eventsExchange).durable(true).type("topic"));
        final Flux<AMQP.Queue.BindOk> bindings = fromIterable(resolver.getEventListeners()).flatMap(listener -> creator.bind(BindingSpecification.binding(eventsExchange, listener.getPath(), queueName)));
        if (withDLQRetry) {
            final String eventsDLQExchangeName = format("%s.%s.DLQ", appName, eventsExchange);
            final String retryExchangeName = format("%s.%s", appName, eventsExchange);
            final Mono<AMQP.Exchange.DeclareOk> retryExchange = creator.declare(ExchangeSpecification.exchange(retryExchangeName).durable(true).type("topic"));
            final Mono<AMQP.Exchange.DeclareOk> declareExchangeDLQ = creator.declare(ExchangeSpecification.exchange(eventsDLQExchangeName).durable(true).type("topic"));
            final Mono<AMQP.Queue.DeclareOk> declareDLQ = creator.declareDLQ(queueName, retryExchangeName, retryDelay, maxLengthBytes);
            final Mono<AMQP.Queue.DeclareOk> declareQueue = creator.declareQueue(queueName, eventsDLQExchangeName, maxLengthBytes);
            final Mono<AMQP.Queue.BindOk> bindingDLQ = creator.bind(BindingSpecification.binding(eventsDLQExchangeName, "#", queueName + ".DLQ"));
            final Mono<AMQP.Queue.BindOk> retryBinding = creator.bind(BindingSpecification.binding(retryExchangeName, "#", queueName));
            return declareExchange.then(retryExchange).then(declareExchangeDLQ).then(declareQueue).then(declareDLQ).thenMany(bindings).then(bindingDLQ).then(retryBinding).then();
        } else {
            final Mono<AMQP.Queue.DeclareOk> declareQueue = creator.declareQueue(queueName, maxLengthBytes);
            return declareExchange.then(declareQueue).thenMany(bindings).then();
        }

    }

    @Override
    protected Function<Message, Mono<Object>> rawMessageHandler(String executorPath) {
        final RegisteredEventListener<Object, Object> handler = resolver.getEventListener(executorPath);

        Function<Message, Object> converter = resolveConverter(handler);
        final EventExecutor<Object> executor = new EventExecutor<>(handler.getHandler(), converter);

        return msj -> executor
                .execute(msj)
                .cast(Object.class);
    }

    protected String getExecutorPath(AcknowledgableDelivery msj) {
        return msj.getEnvelope().getRoutingKey();
    }

    @Override
    protected Object parseMessageForReporter(Message msj) {
        return messageConverter.readDomainEventStructure(msj);
    }

    private <T, D> Function<Message, Object> resolveConverter(RegisteredEventListener<T, D> registeredEventListener) {
        if (registeredEventListener.getHandler() instanceof DomainEventHandler) {
            final Class<T> eventClass = registeredEventListener.getInputClass();
            return msj -> messageConverter.readDomainEvent(msj, eventClass);
        }
        if (registeredEventListener.getHandler() instanceof CloudEventHandler) {
            return messageConverter::readCloudEvent;
        }
        throw new RuntimeException("Unknown handler type");
    }
}


