package org.reactivecommons.async.rabbit;

import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import lombok.SneakyThrows;
import lombok.experimental.UtilityClass;
import lombok.extern.java.Log;
import org.reactivecommons.api.domain.DomainEventBus;
import org.reactivecommons.async.commons.DLQDiscardNotifier;
import org.reactivecommons.async.commons.DiscardNotifier;
import org.reactivecommons.async.commons.config.BrokerConfig;
import org.reactivecommons.async.commons.converters.MessageConverter;
import org.reactivecommons.async.rabbit.communications.ReactiveMessageListener;
import org.reactivecommons.async.rabbit.communications.ReactiveMessageSender;
import org.reactivecommons.async.rabbit.communications.TopologyCreator;
import org.reactivecommons.async.rabbit.config.ConnectionFactoryProvider;
import org.reactivecommons.async.rabbit.config.RabbitProperties;
import org.reactivecommons.async.rabbit.config.props.AsyncProps;
import org.springframework.boot.context.properties.PropertyMapper;
import reactor.core.publisher.Mono;
import reactor.rabbitmq.ChannelPool;
import reactor.rabbitmq.ChannelPoolFactory;
import reactor.rabbitmq.ChannelPoolOptions;
import reactor.rabbitmq.RabbitFlux;
import reactor.rabbitmq.Receiver;
import reactor.rabbitmq.ReceiverOptions;
import reactor.rabbitmq.Sender;
import reactor.rabbitmq.SenderOptions;
import reactor.rabbitmq.Utils;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.logging.Level;

@Log
@UtilityClass
public class RabbitMQSetupUtils {
    private static final String LISTENER_TYPE = "listener";
    private static final String SENDER_TYPE = "sender";

    @SneakyThrows
    public static ConnectionFactoryProvider connectionFactoryProvider(RabbitProperties properties) {
        final ConnectionFactory factory = new ConnectionFactory();
        PropertyMapper map = PropertyMapper.get();
        map.from(properties::determineHost).whenNonNull().to(factory::setHost);
        map.from(properties::determinePort).to(factory::setPort);
        map.from(properties::determineUsername).whenNonNull().to(factory::setUsername);
        map.from(properties::determinePassword).whenNonNull().to(factory::setPassword);
        map.from(properties::determineVirtualHost).whenNonNull().to(factory::setVirtualHost);
        factory.useNio();
        if (properties.getSsl() != null && properties.getSsl().isEnabled()) {
            factory.useSslProtocol();
        }
        return () -> factory;
    }


    public static ReactiveMessageSender createMessageSender(ConnectionFactoryProvider provider,
                                                            AsyncProps props,
                                                            MessageConverter converter) {
        final Sender sender = RabbitFlux.createSender(reactiveCommonsSenderOptions(props.getAppName(), provider,
                props.getConnectionProperties()));
        return new ReactiveMessageSender(sender, props.getAppName(), converter, new TopologyCreator(sender));
    }

    public static ReactiveMessageListener createMessageListener(ConnectionFactoryProvider provider, AsyncProps props) {
        final Mono<Connection> connection =
                createConnectionMono(provider.getConnectionFactory(), props.getAppName(), LISTENER_TYPE);
        final Receiver receiver = RabbitFlux.createReceiver(new ReceiverOptions().connectionMono(connection));
        final Sender sender = RabbitFlux.createSender(new SenderOptions().connectionMono(connection));

        return new ReactiveMessageListener(receiver,
                new TopologyCreator(sender),
                props.getFlux().getMaxConcurrency(),
                props.getPrefetchCount());
    }

    public static TopologyCreator createTopologyCreator(AsyncProps props) {
        ConnectionFactoryProvider provider = connectionFactoryProvider(props.getConnectionProperties());
        final Mono<Connection> connection = createConnectionMono(provider.getConnectionFactory(),
                props.getAppName(), LISTENER_TYPE);
        final Sender sender = RabbitFlux.createSender(new SenderOptions().connectionMono(connection));
        return new TopologyCreator(sender);
    }

    public static DiscardNotifier createDiscardNotifier(ReactiveMessageSender sender, AsyncProps props,
                                                        BrokerConfig brokerConfig, MessageConverter converter) {
        DomainEventBus appDomainEventBus = new RabbitDomainEventBus(sender,
                props.getBrokerConfigProps().getDomainEventsExchangeName(), brokerConfig);
        return new DLQDiscardNotifier(appDomainEventBus, converter);
    }

    private static SenderOptions reactiveCommonsSenderOptions(String appName, ConnectionFactoryProvider provider, RabbitProperties rabbitProperties) {
        final Mono<Connection> senderConnection = createConnectionMono(provider.getConnectionFactory(), appName, SENDER_TYPE);
        final ChannelPoolOptions channelPoolOptions = new ChannelPoolOptions();
        final PropertyMapper map = PropertyMapper.get();

        map.from(rabbitProperties.getCache().getChannel()::getSize).whenNonNull()
                .to(channelPoolOptions::maxCacheSize);

        final ChannelPool channelPool = ChannelPoolFactory.createChannelPool(
                senderConnection,
                channelPoolOptions
        );

        return new SenderOptions()
                .channelPool(channelPool)
                .resourceManagementChannelMono(channelPool.getChannelMono()
                        .transform(Utils::cache));
    }

    private static Mono<Connection> createConnectionMono(ConnectionFactory factory, String connectionPrefix, String connectionType) {
        return Mono.fromCallable(() -> factory.newConnection(connectionPrefix + " " + connectionType))
                .doOnError(err ->
                        log.log(Level.SEVERE, "Error creating connection to RabbitMq Broker. Starting retry process...", err)
                )
                .retryWhen(Retry.backoff(Long.MAX_VALUE, Duration.ofMillis(300))
                        .maxBackoff(Duration.ofMillis(3000)))
                .cache();
    }

}
