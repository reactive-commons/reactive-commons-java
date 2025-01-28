package org.reactivecommons.async.rabbit.standalone.config;

import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import org.reactivecommons.async.commons.converters.MessageConverter;
import org.reactivecommons.async.commons.converters.json.ObjectMapperSupplier;
import org.reactivecommons.async.rabbit.communications.ReactiveMessageSender;
import org.reactivecommons.async.rabbit.communications.TopologyCreator;
import org.reactivecommons.async.rabbit.config.ConnectionFactoryProvider;
import org.reactivecommons.async.rabbit.converters.json.RabbitJacksonMessageConverter;
import reactor.core.publisher.Mono;
import reactor.rabbitmq.ChannelPool;
import reactor.rabbitmq.ChannelPoolFactory;
import reactor.rabbitmq.ChannelPoolOptions;
import reactor.rabbitmq.RabbitFlux;
import reactor.rabbitmq.Sender;
import reactor.rabbitmq.SenderOptions;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.logging.Level;

@Log
@RequiredArgsConstructor
public class RabbitMqConfig {

    private final String appName;

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
        return new RabbitJacksonMessageConverter(objectMapperSupplier.get());
    }

    Mono<Connection> createSenderConnectionMono(ConnectionFactory factory, String name) {
        return Mono.fromCallable(() -> factory.newConnection(name))
                .doOnError(err -> log.log(
                        Level.SEVERE, "Error creating connection to RabbitMq Broker. Starting retry process...", err
                ))
                .retryWhen(Retry.backoff(Long.MAX_VALUE, Duration.ofMillis(300)))
                .cache();
    }

}
