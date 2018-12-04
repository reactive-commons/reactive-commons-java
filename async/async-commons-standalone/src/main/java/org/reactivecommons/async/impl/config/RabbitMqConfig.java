package org.reactivecommons.async.impl.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import lombok.extern.java.Log;
import org.reactivecommons.async.impl.communications.ReactiveMessageListener;
import org.reactivecommons.async.impl.communications.ReactiveMessageSender;
import org.reactivecommons.async.impl.communications.TopologyCreator;
import org.reactivecommons.async.impl.converters.JacksonMessageConverter;
import org.reactivecommons.async.impl.converters.MessageConverter;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.rabbitmq.*;

import java.time.Duration;
import java.util.logging.Level;

@Log
public class RabbitMqConfig {

    private String appName;

    public RabbitMqConfig(String appName) {
        this.appName = appName;
    }

    public ReactiveMessageSender messageSender(ConnectionFactoryProvider provider, MessageConverter converter){
        final Mono<Connection> senderConnection = createSenderConnectionMono(provider.getConnectionFactory(), "sender");
        final Sender sender = ReactorRabbitMq.createSender(new SenderOptions().connectionMono(senderConnection));
        return new ReactiveMessageSender(sender, appName, converter, new TopologyCreator(senderConnection));
    }

    public ReactiveMessageListener messageListener(ConnectionFactoryProvider provider) {
        final Mono<Connection> connection = createSenderConnectionMono(provider.getConnectionFactory(), "listener");
        Receiver receiver = ReactorRabbitMq.createReceiver(new ReceiverOptions().connectionMono(connection));
        return new ReactiveMessageListener(receiver, new TopologyCreator(connection));
    }

    public ConnectionFactoryProvider connectionFactory(RabbitProperties properties){
        final ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(properties.getHost());
        factory.setPort(properties.getPort());
        factory.setUsername(properties.getUsername());
        factory.setPassword(properties.getPassword());
        factory.setVirtualHost(properties.getVirtualHost() != null ? properties.getVirtualHost() : "/");
        factory.setAutomaticRecoveryEnabled(true);
        factory.setTopologyRecoveryEnabled(true);
        factory.useNio();
        return () -> factory;
    }

    public MessageConverter messageConverter(){
        return new JacksonMessageConverter(new ObjectMapper());
    }

    Mono<Connection> createSenderConnectionMono(ConnectionFactory factory, String name){
        final Scheduler senderScheduler = Schedulers.newElastic(name + "_scheduler");
        return Mono.fromCallable(() -> factory.newConnection(name))
            .doOnError(err ->
                log.log(Level.SEVERE, "Error creating connection to RabbitMq Broker. Starting retry process...", err)
            )
            .retryBackoff(Long.MAX_VALUE, Duration.ofMillis(300), Duration.ofMillis(3000))
            .subscribeOn(senderScheduler)
            .cache();
    }

}
