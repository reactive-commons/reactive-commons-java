package org.reactivecommons.async.rabbit.listeners;

import com.rabbitmq.client.AMQP;
import lombok.extern.java.Log;
import org.reactivecommons.api.domain.DomainEvent;
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

    private final boolean createTopology;


    public ApplicationNotificationListener(ReactiveMessageListener receiver,
                                           String exchangeName,
                                           String queueName,
                                           boolean createTopology,
                                           HandlerResolver handlerResolver,
                                           MessageConverter messageConverter,
                                           DiscardNotifier discardNotifier,
                                           CustomReporter errorReporter) {
        super(queueName, receiver, false, true, 1, discardNotifier, "event", errorReporter);
        this.resolver = handlerResolver;
        this.messageConverter = messageConverter;
        this.exchangeName = exchangeName;
        this.createTopology = createTopology;
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

        if (createTopology) {
            return declareExchange
                    .then(declareQueue)
                    .thenMany(bindings)
                    .then();
        } else {
            return declareQueue
                    .thenMany(bindings)
                    .then();
        }
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
