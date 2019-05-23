package org.reactivecommons.async.impl.config;

import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import lombok.extern.java.Log;
import org.reactivecommons.async.impl.communications.ReactiveMessageListener;
import org.reactivecommons.async.impl.communications.ReactiveMessageSender;
import org.reactivecommons.async.impl.communications.TopologyCreator;
import org.reactivecommons.async.impl.config.props.BrokerConfigProps;
import org.reactivecommons.async.impl.converters.JacksonMessageConverter;
import org.reactivecommons.async.impl.converters.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.PropertyMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.rabbitmq.*;

import java.time.Duration;
import java.util.logging.Level;

@Log
@Configuration
@EnableConfigurationProperties(RabbitProperties.class)
@Import(BrokerConfigProps.class)
public class RabbitMqConfig {

    @Value("${app.async.flux.maxConcurrency:250}")
    private Integer maxConcurrency;

    @Bean
    public ReactiveMessageSender messageSender(ConnectionFactoryProvider provider, MessageConverter converter, BrokerConfigProps props){
        final Mono<Connection> senderConnection = createSenderConnectionMono(provider.getConnectionFactory(), "sender");
        final Sender sender = RabbitFlux.createSender(new SenderOptions().connectionMono(senderConnection));
        return new ReactiveMessageSender(sender, props.getAppName(), converter, new TopologyCreator(senderConnection));
    }

    @Bean
    public ReactiveMessageListener messageListener(ConnectionFactoryProvider provider) {
        final Mono<Connection> connection = createSenderConnectionMono(provider.getConnectionFactory(), "listener");
        Receiver receiver = RabbitFlux.createReceiver(new ReceiverOptions().connectionMono(connection));
        return new ReactiveMessageListener(receiver, new TopologyCreator(connection), maxConcurrency);
    }

    @Bean
    @ConditionalOnMissingBean
    public ConnectionFactoryProvider connectionFactory(RabbitProperties properties){
        final ConnectionFactory factory = new ConnectionFactory();
        PropertyMapper map = PropertyMapper.get();
        map.from(properties::determineHost).whenNonNull().to(factory::setHost);
        map.from(properties::determinePort).to(factory::setPort);
        map.from(properties::determineUsername).whenNonNull().to(factory::setUsername);
        map.from(properties::determinePassword).whenNonNull().to(factory::setPassword);
        map.from(properties::determineVirtualHost).whenNonNull().to(factory::setVirtualHost);
        map.from(properties::getRequestedHeartbeat).whenNonNull().asInt(Duration::getSeconds).to(factory::setRequestedHeartbeat);
        factory.setAutomaticRecoveryEnabled(true);
        factory.setTopologyRecoveryEnabled(true);
        factory.useNio();
        return () -> factory;
    }

    @Bean
    @ConditionalOnMissingBean
    public MessageConverter messageConverter(){
        return new JacksonMessageConverter();
    }

    Mono<Connection> createSenderConnectionMono(ConnectionFactory factory, String name){
        final Scheduler senderScheduler = Schedulers.elastic();
        return Mono.fromCallable(() -> factory.newConnection(name))
            .doOnError(err ->
                log.log(Level.SEVERE, "Error creating connection to RabbitMq Broker. Starting retry process...", err)
            )
            .retryBackoff(Long.MAX_VALUE, Duration.ofMillis(300), Duration.ofMillis(3000))
            .subscribeOn(senderScheduler)
            .cache();
    }

}
