package org.reactivecommons.async.impl.config;

import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import lombok.extern.java.Log;
import org.reactivecommons.async.impl.communications.ReactiveMessageSender;
import org.reactivecommons.async.impl.communications.TopologyCreator;
import org.reactivecommons.async.parent.converters.MessageConverter;
import org.reactivecommons.async.impl.converters.json.JacksonMessageConverter;
import org.reactivecommons.async.parent.converters.json.ObjectMapperSupplier;
import reactor.core.publisher.Mono;
import reactor.rabbitmq.*;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.logging.Level;

@Log
public class RabbitMqConfig {

    private String appName;

    public RabbitMqConfig(String appName) {
        this.appName = appName;
    }

    public ReactiveMessageSender messageSender(ConnectionFactoryProvider provider, MessageConverter converter,
                                               RabbitProperties rabbitProperties) {
        final Mono<Connection> senderConnection = createSenderConnectionMono(provider.getConnectionFactory(), "sender");

        ChannelPool channelPool = ChannelPoolFactory.createChannelPool(
                senderConnection,
                new ChannelPoolOptions().maxCacheSize(rabbitProperties.getChannelPoolMaxCacheSize())
        );

        final Sender sender = RabbitFlux.createSender(new SenderOptions().channelPool(channelPool));

        return new ReactiveMessageSender(sender, appName, converter, new TopologyCreator(sender));
    }

    /*public ReactiveMessageListener messageListener(ConnectionFactoryProvider provider) {
        final Mono<Connection> connection = createSenderConnectionMono(provider.getConnectionFactory(), "listener");
        Receiver receiver = RabbitFlux.createReceiver(new ReceiverOptions().connectionMono(connection));
        return new ReactiveMessageListener(receiver, new TopologyCreator(connection));
    }*/

    public ConnectionFactoryProvider connectionFactory(RabbitProperties properties) {
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

    public MessageConverter messageConverter(ObjectMapperSupplier objectMapperSupplier) {
        return new JacksonMessageConverter(objectMapperSupplier.get());
    }

    Mono<Connection> createSenderConnectionMono(ConnectionFactory factory, String name) {
        return Mono.fromCallable(() -> factory.newConnection(name))
                .doOnError(err ->
                        log.log(Level.SEVERE, "Error creating connection to RabbitMq Broker. Starting retry process...", err)
                )
                .retryWhen(Retry.backoff(Long.MAX_VALUE, Duration.ofMillis(300)))
                .cache();
    }

}
