package org.reactivecommons.async.impl.communications;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import lombok.extern.java.Log;
import reactor.core.publisher.Mono;
import reactor.rabbitmq.BindingSpecification;
import reactor.rabbitmq.ExchangeSpecification;
import reactor.rabbitmq.QueueSpecification;
import reactor.rabbitmq.Sender;

import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;

@Log
/*
 Direct use of channel is temporal, remove when https://github.com/reactor/reactor-rabbitmq/issues/37 is fixed in 1.0.0.RC2
 */
public class TopologyCreator {

    private final Sender sender;

    public TopologyCreator(Sender sender) {
        this.sender = sender;
    }

    public Mono<AMQP.Exchange.DeclareOk> declare(ExchangeSpecification exchange) {
        return sender.declare(exchange)
                .onErrorMap(TopologyDefException::new);
    }

    public Mono<AMQP.Queue.DeclareOk> declare(QueueSpecification queue) {
        return sender.declare(queue)
                .onErrorMap(TopologyDefException::new);
    }

    public Mono<AMQP.Queue.BindOk> bind(BindingSpecification binding) {
        return sender.bind(binding)
                .onErrorMap(TopologyDefException::new);
    }

    public Mono<AMQP.Queue.UnbindOk> unbind(BindingSpecification binding) {
        return sender.unbind(binding)
                .onErrorMap(TopologyDefException::new);
    }

    public Mono<AMQP.Queue.DeclareOk> declareDLQ(String originQueue, String retryTarget, int retryTime){
        final Map<String, Object> args = new HashMap<>();
        args.put("x-dead-letter-exchange", retryTarget);
        args.put("x-message-ttl", retryTime);
        QueueSpecification specification = QueueSpecification.queue(originQueue + ".DLQ")
                .durable(true)
                .arguments(args);
        return declare(specification);
    }

    public Mono<AMQP.Queue.DeclareOk> declareQueue(String name, String dlqExchange){
        final Map<String, Object> args = new HashMap<>();
        args.put("x-dead-letter-exchange", dlqExchange);
        QueueSpecification specification = QueueSpecification.queue(name)
                .durable(true)
                .arguments(args);
        return declare(specification);
    }

    public static class TopologyDefException extends RuntimeException {
        public TopologyDefException(Throwable cause) {
            super(cause);
        }
    }
}
