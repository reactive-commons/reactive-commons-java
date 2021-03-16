package org.reactivecommons.async.impl.listeners;

import com.rabbitmq.client.AMQP;
import lombok.extern.java.Log;
import org.reactivecommons.api.domain.DomainEvent;
import org.reactivecommons.async.api.handlers.registered.RegisteredEventListener;
import org.reactivecommons.async.impl.DiscardNotifier;
import org.reactivecommons.async.impl.EventExecutor;
import org.reactivecommons.async.impl.HandlerResolver;
import org.reactivecommons.async.parent.communications.Message;
import org.reactivecommons.async.impl.communications.ReactiveMessageListener;
import org.reactivecommons.async.impl.communications.TopologyCreator;
import org.reactivecommons.async.parent.converters.MessageConverter;
import org.reactivecommons.async.parent.ext.CustomErrorReporter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.rabbitmq.AcknowledgableDelivery;

import java.util.function.Function;

import static reactor.core.publisher.Flux.fromIterable;
import static reactor.rabbitmq.BindingSpecification.binding;
import static reactor.rabbitmq.ExchangeSpecification.exchange;
import static reactor.rabbitmq.QueueSpecification.queue;

@Log
public class ApplicationNotificationListener extends GenericMessageListener {

    private final MessageConverter messageConverter;
    private final HandlerResolver resolver;
    private final String exchangeName;


    public ApplicationNotificationListener(ReactiveMessageListener receiver,
                                           String exchangeName,
                                           String queueName,
                                           HandlerResolver handlerResolver,
                                           MessageConverter messageConverter,
                                           DiscardNotifier discardNotifier,
                                           CustomErrorReporter errorReporter) {
        super(queueName,receiver,false,1,discardNotifier,"event", errorReporter);
        this.resolver = handlerResolver;
        this.messageConverter = messageConverter;
        this.exchangeName = exchangeName;
    }

    protected Mono<Void> setUpBindings(TopologyCreator creator) {
        final Mono<AMQP.Exchange.DeclareOk> declareExchange = creator.declare(exchange(exchangeName)
                .type("topic")
                .durable(true));

        final Mono<AMQP.Queue.DeclareOk> declareQueue = creator.declare(
                queue(queueName)
                .durable(false)
                .autoDelete(true)
                .exclusive(true));

        final Flux<AMQP.Queue.BindOk> bindings = fromIterable(resolver.getNotificationListeners())
                .flatMap(listener -> creator.bind(binding(exchangeName, listener.getPath(), queueName)));

        return declareExchange
                .then(declareQueue)
                .thenMany(bindings)
                .then();
    }

    @Override
    protected Function<Message, Mono<Object>> rawMessageHandler(String executorPath) {
        final RegisteredEventListener<Object> eventListener = resolver.getNotificationListener(executorPath);
        final Function<Message, DomainEvent<Object>> converter = message -> messageConverter
                .readDomainEvent(message, eventListener.getInputClass());
        final EventExecutor<Object> executor = new EventExecutor<>(eventListener.getHandler(), converter);
        return message -> executor
                .execute(message)
                .cast(Object.class);
    }

    @Override
    protected String getExecutorPath(AcknowledgableDelivery message) {
        return message.getEnvelope()
                .getRoutingKey();
    }

    @Override
    protected Object parseMessageForReporter(Message msj) {
        return messageConverter.readDomainEventStructure(msj);
    }
}
