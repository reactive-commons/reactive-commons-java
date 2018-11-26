package org.reactivecommons.async.impl.communications;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import lombok.extern.java.Log;
import reactor.core.publisher.Mono;
import reactor.rabbitmq.BindingSpecification;
import reactor.rabbitmq.ExchangeSpecification;
import reactor.rabbitmq.QueueSpecification;

import java.io.IOException;
import java.time.Duration;
import java.util.logging.Level;

@Log
/*
 Direct use of channel is temporal, remove when https://github.com/reactor/reactor-rabbitmq/issues/37 is fixed in 1.0.0.RC2
 */
public class TopologyCreator {

    private final Mono<Channel> channel;

    public TopologyCreator(Mono<Connection> connectionMono) {
        this.channel = connectionMono.map(connection -> {
            try {
                return connection.createChannel();
            } catch (IOException e) {
                throw new TopologyDefException("Fail to create channel", e);
            }
        }).doOnError(e -> log.log(Level.SEVERE, e.getMessage(), e))
        .retryBackoff(5, Duration.ofMillis(500))
        .cache();
    }

    public Mono<AMQP.Exchange.DeclareOk> declare(ExchangeSpecification exchange){
        return channel.map(ch -> {
            try {
                return ch.exchangeDeclare(exchange.getName(), exchange.getType(), exchange.isDurable(), exchange.isAutoDelete(), exchange.getArguments());
            } catch (IOException e) {
                throw new TopologyDefException("Fail to declare exchange: " + exchange.getName(), e);
            }
        });
    }

    public Mono<AMQP.Queue.DeclareOk> declare(QueueSpecification queue){
        return channel.map(ch -> {
            try {
                return ch.queueDeclare(queue.getName(), queue.isDurable(), queue.isExclusive(), queue.isAutoDelete(), queue.getArguments());
            } catch (IOException e) {
                throw new TopologyDefException("Fail to declare queue: " + queue.getName(), e);
            }
        });
    }

    public Mono<AMQP.Queue.BindOk> bind(BindingSpecification binding){
        return channel.map(ch -> {
            try {
                return ch.queueBind(binding.getQueue(), binding.getExchange(), binding.getRoutingKey(), binding.getArguments());
            } catch (IOException e) {
                throw new TopologyDefException("Fail to bind queue: " + binding.getQueue(), e);
            }
        });
    }

    public static class TopologyDefException extends RuntimeException {
        public TopologyDefException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
