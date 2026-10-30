package org.reactivecommons.async.rabbit.listeners;

import com.rabbitmq.client.AMQP;
import org.reactivecommons.async.api.handlers.CloudEventHandler;
import org.reactivecommons.async.api.handlers.DomainEventHandler;
import org.reactivecommons.async.api.handlers.RawEventHandler;
import org.reactivecommons.async.commons.ListenerContext;
import org.reactivecommons.async.commons.RetryPolicy;
import org.reactivecommons.async.commons.exceptions.MissingHandlerException;
import org.reactivecommons.async.api.handlers.registered.RegisteredEventListener;
import org.reactivecommons.async.commons.EventExecutor;
import org.reactivecommons.async.commons.HandlerResolver;
import org.reactivecommons.async.commons.communications.Message;
import org.reactivecommons.async.commons.converters.MessageConverter;
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

public class ApplicationEventListener extends GenericMessageListener {

    private final MessageConverter messageConverter;
    private final HandlerResolver resolver;
    private final String eventsExchange;
    private final RetryPolicy retryPolicy;
    private final Optional<Integer> maxLengthBytes;
    private final String appName;


    public ApplicationEventListener(ListenerContext<ReactiveMessageListener> context, String queueName,
                                    String eventsExchange, Optional<Integer> maxLengthBytes, String appName) {
        super(context, queueName, "event");
        this.retryPolicy = context.retryPolicy();
        this.resolver = context.resolver();
        this.eventsExchange = eventsExchange;
        this.messageConverter = context.converter();
        this.maxLengthBytes = maxLengthBytes;
        this.appName = appName;
    }

    @Override
    protected Mono<Void> setUpBindings(TopologyCreator creator) {
        final Mono<AMQP.Exchange.DeclareOk> declareExchange = creator.declare(
                ExchangeSpecification.exchange(eventsExchange).durable(true).type("topic")
        );
        // one at a time: they share the channel of the creator, which takes a single RPC at a time. Read on every
        // subscription (e.g. a restart of the listener): the events whose listening was stopped are not bound again
        final Flux<AMQP.Queue.BindOk> bindings = Flux.defer(() -> fromIterable(resolver.getEventListeners()))
                .concatMap(listener -> creator.bind(
                        BindingSpecification.binding(eventsExchange, listener.path(), queueName)
                ));
        if (retryPolicy.withDLQRetry()) {
            final String eventsDLQExchangeName = format("%s.%s.DLQ", appName, eventsExchange);
            final String retryExchangeName = format("%s.%s", appName, eventsExchange);
            final Mono<AMQP.Exchange.DeclareOk> retryExchange = creator.declare(
                    ExchangeSpecification.exchange(retryExchangeName).durable(true).type("topic")
            );
            final Mono<AMQP.Exchange.DeclareOk> declareExchangeDLQ = creator.declare(
                    ExchangeSpecification.exchange(eventsDLQExchangeName).durable(true).type("topic")
            );
            final Mono<AMQP.Queue.DeclareOk> declareDLQ = creator.declareDLQ(
                    queueName, retryExchangeName, retryPolicy.retryDelay(), maxLengthBytes
            );
            final Mono<AMQP.Queue.DeclareOk> declareQueue = creator.declareQueue(
                    queueName, eventsDLQExchangeName, maxLengthBytes
            );
            final Mono<AMQP.Queue.BindOk> bindingDLQ = creator.bind(
                    BindingSpecification.binding(eventsDLQExchangeName, "#", queueName + ".DLQ")
            );
            final Mono<AMQP.Queue.BindOk> retryBinding = creator.bind(
                    BindingSpecification.binding(retryExchangeName, "#", queueName)
            );
            return declareExchange.then(retryExchange)
                    .then(declareExchangeDLQ)
                    .then(declareQueue)
                    .then(declareDLQ)
                    .thenMany(bindings)
                    .then(bindingDLQ)
                    .then(retryBinding)
                    .then();
        } else {
            final Mono<AMQP.Queue.DeclareOk> declareQueue = creator.declareQueue(queueName, maxLengthBytes);
            return declareExchange.then(declareQueue).thenMany(bindings).then();
        }

    }

    @Override
    protected Function<Message, Mono<Object>> rawMessageHandler(String executorPath) {
        final RegisteredEventListener<Object, Object> handler = resolver.getEventListener(executorPath);
        if (handler == null) {
            throw new MissingHandlerException("event", executorPath); // not cached: a dynamic one may come later
        }

        Function<Message, Object> converter = resolveConverter(handler);
        final EventExecutor<Object> executor = new EventExecutor<>(handler.handler(), converter);

        return msj -> executor
                .execute(msj)
                .cast(Object.class);
    }

    protected String getExecutorPath(AcknowledgableDelivery msj) {
        return msj.getEnvelope().getRoutingKey();
    }

    @Override
    protected String handlerName(String executorPath) {
        final RegisteredEventListener<Object, Object> handler = resolver.getEventListener(executorPath);
        return handler != null ? handler.path() : null;
    }

    @Override
    protected Object parseMessageForReporter(Message msj) {
        return messageConverter.readDomainEventStructure(msj);
    }

    private <T, D> Function<Message, Object> resolveConverter(RegisteredEventListener<T, D> registeredEventListener) {
        if (registeredEventListener.handler() instanceof DomainEventHandler) {
            final Class<T> eventClass = registeredEventListener.inputClass();
            return msj -> messageConverter.readDomainEvent(msj, eventClass);
        }
        if (registeredEventListener.handler() instanceof CloudEventHandler) {
            return messageConverter::readCloudEvent;
        }
        if (registeredEventListener.handler() instanceof RawEventHandler) {
            return message -> message;
        }
        throw new RuntimeException("Unknown handler type");
    }

    @Override
    protected String getKind() {
        return "events";
    }
}


