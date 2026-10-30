package org.reactivecommons.async.rabbit.listeners;

import com.rabbitmq.client.AMQP;
import org.reactivecommons.async.api.handlers.CloudEventHandler;
import org.reactivecommons.async.api.handlers.DomainEventHandler;
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
import reactor.rabbitmq.AcknowledgableDelivery;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.function.Function;

import static reactor.rabbitmq.BindingSpecification.binding;
import static reactor.rabbitmq.ExchangeSpecification.exchange;
import static reactor.rabbitmq.QueueSpecification.queue;
import static reactor.core.publisher.Flux.fromIterable;

public class ApplicationNotificationListener extends GenericMessageListener {

    /**
     * A notification whose handler failed is retried once by the listener, 200 ms later, whatever the retry policy
     * of the domain.
     */
    private static final RetryPolicy NOTIFICATION_RETRIES = new RetryPolicy(false, 1, 200);

    private final MessageConverter messageConverter;
    private final HandlerResolver resolver;
    private final String exchangeName;

    private final boolean createTopology;


    public ApplicationNotificationListener(ListenerContext<ReactiveMessageListener> context, String exchangeName,
                                           String queueName) {
        super(context.withRetryPolicy(NOTIFICATION_RETRIES).withCreateTopology(true), queueName, "event");
        this.resolver = context.resolver();
        this.messageConverter = context.converter();
        this.exchangeName = exchangeName;
        this.createTopology = context.createTopology();
    }

    @Override
    protected Mono<Void> setUpBindings(TopologyCreator creator) {

        final Mono<AMQP.Queue.DeclareOk> declareQueue = creator.declare(
                queue(queueName).durable(false).autoDelete(true).exclusive(true)
        );

        // one at a time: they share the channel of the creator, which takes a single RPC at a time
        final Flux<AMQP.Queue.BindOk> bindings = fromIterable(resolver.getNotificationListeners())
                .concatMap(listener -> creator.bind(
                        binding(exchangeName, listener.path(), queueName))
                );

        if (createTopology) {
            return creator.declare(exchange(exchangeName).type("topic").durable(true))
                    .then(declareQueue)
                    .thenMany(bindings)
                    .then();
        }
        return declareQueue.thenMany(bindings).then();
    }

    @Override
    protected Function<Message, Mono<Object>> rawMessageHandler(String executorPath) {
        final RegisteredEventListener<Object, Object> eventListener = resolver.getNotificationListener(executorPath);
        if (eventListener == null) {
            throw new MissingHandlerException("notification", executorPath);
        }

        Function<Message, Object> converter = resolveConverter(eventListener);
        final EventExecutor<Object> executor = new EventExecutor<>(eventListener.handler(), converter);

        return message -> executor.execute(message).cast(Object.class);
    }

    @Override
    protected String getExecutorPath(AcknowledgableDelivery message) {
        return message.getEnvelope().getRoutingKey();
    }

    @Override
    protected String handlerName(String executorPath) {
        final RegisteredEventListener<Object, Object> eventListener = resolver.getNotificationListener(executorPath);
        return eventListener != null ? eventListener.path() : null;
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
        throw new RuntimeException("Unknown handler type");
    }

    @Override
    protected String getKind() {
        return "notifications";
    }
}
