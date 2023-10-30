package org.reactivecommons.async.rabbit.listeners;

import com.rabbitmq.client.AMQP;
import lombok.extern.java.Log;
import org.reactivecommons.api.domain.Command;
import org.reactivecommons.async.api.handlers.registered.RegisteredCommandHandler;
import org.reactivecommons.async.commons.CommandExecutor;
import org.reactivecommons.async.commons.DiscardNotifier;
import org.reactivecommons.async.commons.communications.Message;
import org.reactivecommons.async.commons.converters.MessageConverter;
import org.reactivecommons.async.commons.ext.CustomReporter;
import org.reactivecommons.async.rabbit.HandlerResolver;
import org.reactivecommons.async.rabbit.RabbitMessage;
import org.reactivecommons.async.rabbit.communications.ReactiveMessageListener;
import org.reactivecommons.async.rabbit.communications.TopologyCreator;
import reactor.core.publisher.Mono;
import reactor.rabbitmq.AcknowledgableDelivery;
import reactor.rabbitmq.BindingSpecification;
import reactor.rabbitmq.ExchangeSpecification;

import java.util.Optional;
import java.util.function.Function;

@Log
public class ApplicationCommandListener extends GenericMessageListener {

    private final MessageConverter messageConverter;
    private final HandlerResolver resolver;
    private final String directExchange;
    private final boolean withDLQRetry;
    private final boolean delayedCommands;
    private final int retryDelay;
    private final Optional<Integer> maxLengthBytes;

    //TODO: change large constructor parameters number
    public ApplicationCommandListener(ReactiveMessageListener listener, String queueName, HandlerResolver resolver, String directExchange, MessageConverter messageConverter, boolean withDLQRetry, boolean delayedCommands, long maxRetries, int retryDelay, Optional<Integer> maxLengthBytes, DiscardNotifier discardNotifier, CustomReporter errorReporter) {
        super(queueName, listener, withDLQRetry, maxRetries, discardNotifier, "command", errorReporter);
        this.retryDelay = retryDelay;
        this.withDLQRetry = withDLQRetry;
        this.delayedCommands = delayedCommands;
        this.resolver = resolver;
        this.directExchange = directExchange;
        this.messageConverter = messageConverter;
        this.maxLengthBytes = maxLengthBytes;
    }

    protected Mono<Void> setUpBindings(TopologyCreator creator) {
        final Mono<AMQP.Exchange.DeclareOk> declareExchange = creator.declare(ExchangeSpecification.exchange(directExchange).durable(true).type("direct"));
        if (withDLQRetry) {
            final Mono<AMQP.Exchange.DeclareOk> declareExchangeDLQ = creator.declare(ExchangeSpecification.exchange(directExchange + ".DLQ").durable(true).type("direct"));
            final Mono<AMQP.Queue.DeclareOk> declareQueue = creator.declareQueue(queueName, directExchange + ".DLQ", maxLengthBytes);
            final Mono<AMQP.Queue.DeclareOk> declareDLQ = creator.declareDLQ(queueName, directExchange, retryDelay, maxLengthBytes);
            final Mono<AMQP.Queue.BindOk> binding = creator.bind(BindingSpecification.binding(directExchange, queueName, queueName));
            final Mono<AMQP.Queue.BindOk> bindingDLQ = creator.bind(BindingSpecification.binding(directExchange + ".DLQ", queueName, queueName + ".DLQ"));
            return declareExchange.then(declareExchangeDLQ)
                    .then(declareDLQ)
                    .then(declareQueue)
                    .then(bindingDLQ)
                    .then(binding)
                    .then(declareDelayedTopology(creator))
                    .then();
        } else {
            final Mono<AMQP.Queue.DeclareOk> declareQueue = creator.declareQueue(queueName, maxLengthBytes);
            final Mono<AMQP.Queue.BindOk> binding = creator.bind(BindingSpecification.binding(directExchange, queueName, queueName));
            return declareExchange.then(declareQueue).then(binding).then(declareDelayedTopology(creator)).then();
        }
    }

    private Mono<Void> declareDelayedTopology(TopologyCreator creator) {
        if (delayedCommands) {
            String delayedQueue = queueName + "-delayed";
            final Mono<AMQP.Queue.DeclareOk> declareQueue = creator.declareQueue(delayedQueue, directExchange, maxLengthBytes, Optional.of(queueName));
            final Mono<AMQP.Queue.BindOk> binding = creator.bind(BindingSpecification.binding(directExchange, delayedQueue, delayedQueue));
            return declareQueue.then(binding).then();
        }
        return Mono.empty();
    }


    @Override
    protected Function<Message, Mono<Object>> rawMessageHandler(String executorPath) {
        final RegisteredCommandHandler<Object> handler = resolver.getCommandHandler(executorPath);
        final Class<Object> eventClass = handler.getInputClass();
        Function<Message, Command<Object>> converter = msj -> messageConverter.readCommand(msj, eventClass);
        final CommandExecutor<Object> executor = new CommandExecutor<>(handler.getHandler(), converter);
        return msj -> executor.execute(msj).cast(Object.class);
    }

    protected String getExecutorPath(AcknowledgableDelivery msj) {
        final Command<Object> command = messageConverter.readCommandStructure(RabbitMessage.fromDelivery(msj));
        return command.getName();
    }

    @Override
    protected Object parseMessageForReporter(Message message) {
        return messageConverter.readCommandStructure(message);
    }


}


