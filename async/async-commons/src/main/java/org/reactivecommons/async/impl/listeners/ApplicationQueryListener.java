package org.reactivecommons.async.impl.listeners;

import com.rabbitmq.client.AMQP;
import lombok.extern.java.Log;
import org.reactivecommons.async.api.handlers.QueryHandler;
import org.reactivecommons.async.impl.communications.Message;
import org.reactivecommons.async.impl.converters.MessageConverter;
import org.reactivecommons.async.impl.QueryExecutor;
import org.reactivecommons.async.impl.HandlerResolver;
import org.reactivecommons.async.impl.communications.ReactiveMessageListener;
import org.reactivecommons.async.impl.communications.ReactiveMessageSender;
import org.reactivecommons.async.impl.communications.TopologyCreator;
import reactor.core.publisher.Mono;
import reactor.rabbitmq.*;
import reactor.util.function.Tuple2;

import java.lang.reflect.ParameterizedType;
import java.util.HashMap;
import java.util.function.Function;

@Log
public class ApplicationQueryListener extends GenericMessageListener {


    private final MessageConverter converter;
    private final HandlerResolver handlerResolver;
    private final ReactiveMessageSender sender;
    private final String replyExchange;
    private final String directExchange;


    public ApplicationQueryListener(ReactiveMessageListener listener, String queueName, HandlerResolver resolver, ReactiveMessageSender sender, String directExchange, MessageConverter converter, String replyExchange) {
        super(queueName, listener);
        this.converter = converter;
        this.handlerResolver = resolver;
        this.sender = sender;
        this.replyExchange = replyExchange;
        this.directExchange = directExchange;
    }


    @Override
    protected Function<Message, Mono<Object>> rawMessageHandler(String executorPath) {
        final QueryHandler<Object, Object> handler1 = handlerResolver.getQueryHandler(executorPath);
        ParameterizedType genericSuperclass = (ParameterizedType) handler1.getClass().getGenericInterfaces()[0];
        final Class<?> handlerClass = (Class<?>) genericSuperclass.getActualTypeArguments()[1];
        Function<Message, Object> messageConverter = msj -> converter.readAsyncQuery(msj, handlerClass).getQueryData();
        final QueryExecutor<Object, Object> executor = new QueryExecutor<>(handler1, messageConverter);
        return executor::execute;
    }

    protected Mono<Void> setUpBindings(TopologyCreator creator) {
        final Mono<AMQP.Exchange.DeclareOk> declareExchange = creator.declare(ExchangeSpecification.exchange(directExchange).durable(true).type("direct"));
        final Mono<AMQP.Queue.DeclareOk> declareQueue = creator.declare(QueueSpecification.queue(queueName).durable(true));
        final Mono<AMQP.Queue.BindOk> binding = creator.bind(BindingSpecification.binding(directExchange, queueName, queueName));
        return declareExchange.then(declareQueue).then(binding).then();
    }

    @Override
    protected String getExecutorPath(AcknowledgableDelivery msj) {
        return msj.getProperties().getHeaders().get("x-serveQuery-id").toString();
    }

    @Override
    protected Mono<Object> enrichPostProcess(Mono<Tuple2<Object, AcknowledgableDelivery>> flow) {
        return flow.flatMap(o -> {
            final String replyID = o.getT2().getProperties().getHeaders().get("x-reply_id").toString();
            final String correlationID = o.getT2().getProperties().getHeaders().get("x-correlation-id").toString();
            final HashMap<String, Object> headers = new HashMap<>();
            headers.put("x-correlation-id", correlationID);
            return sender.sendWithConfirm(o.getT1(),replyExchange, replyID, headers);
        });
    }

}


