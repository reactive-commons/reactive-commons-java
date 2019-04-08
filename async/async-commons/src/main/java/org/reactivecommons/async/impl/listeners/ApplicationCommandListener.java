package org.reactivecommons.async.impl.listeners;

import com.rabbitmq.client.AMQP;
import lombok.extern.java.Log;
import org.reactivecommons.api.domain.Command;
import org.reactivecommons.async.impl.communications.Message;
import org.reactivecommons.async.impl.converters.MessageConverter;
import org.reactivecommons.async.api.handlers.registered.RegisteredCommandHandler;
import org.reactivecommons.async.impl.CommandExecutor;
import org.reactivecommons.async.impl.HandlerResolver;
import org.reactivecommons.async.impl.RabbitMessage;
import org.reactivecommons.async.impl.communications.ReactiveMessageListener;
import org.reactivecommons.async.impl.communications.TopologyCreator;
import org.reactivecommons.async.impl.converters.JacksonMessageConverter;
import reactor.core.publisher.Mono;
import reactor.rabbitmq.AcknowledgableDelivery;
import reactor.rabbitmq.BindingSpecification;
import reactor.rabbitmq.ExchangeSpecification;
import reactor.rabbitmq.QueueSpecification;

import java.util.function.Function;

@Log
public class ApplicationCommandListener extends GenericMessageListener {

    private final MessageConverter messageConverter;
    private final HandlerResolver resolver;
    private final String directExchange;


    public ApplicationCommandListener(ReactiveMessageListener listener, String queueName, HandlerResolver resolver, String directExchange, MessageConverter messageConverter) {
        super(queueName, listener);
        this.resolver = resolver;
        this.directExchange = directExchange;
        this.messageConverter = messageConverter;
    }

    protected Mono<Void> setUpBindings(TopologyCreator creator) {
        final Mono<AMQP.Exchange.DeclareOk> declareExchange = creator.declare(ExchangeSpecification.exchange(directExchange).durable(true).type("direct"));
        final Mono<AMQP.Queue.DeclareOk> declareQueue = creator.declare(QueueSpecification.queue(queueName).durable(true));
        final Mono<AMQP.Queue.BindOk> binding = creator.bind(BindingSpecification.binding(directExchange, queueName, queueName));
        return declareExchange.then(declareQueue).then(binding).then();
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



}


