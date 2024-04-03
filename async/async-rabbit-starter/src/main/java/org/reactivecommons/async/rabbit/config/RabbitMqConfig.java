package org.reactivecommons.async.rabbit.config;

import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import org.reactivecommons.api.domain.Command;
import org.reactivecommons.api.domain.DomainEvent;
import org.reactivecommons.api.domain.DomainEventBus;
import org.reactivecommons.async.api.AsyncQuery;
import org.reactivecommons.async.api.DefaultCommandHandler;
import org.reactivecommons.async.api.DefaultQueryHandler;
import org.reactivecommons.async.api.DynamicRegistry;
import org.reactivecommons.async.api.HandlerRegistry;
import org.reactivecommons.async.api.handlers.registered.RegisteredCommandHandler;
import org.reactivecommons.async.api.handlers.registered.RegisteredEventListener;
import org.reactivecommons.async.api.handlers.registered.RegisteredQueryHandler;
import org.reactivecommons.async.commons.DiscardNotifier;
import org.reactivecommons.async.commons.communications.Message;
import org.reactivecommons.async.commons.config.BrokerConfig;
import org.reactivecommons.async.commons.config.IBrokerConfigProps;
import org.reactivecommons.async.commons.converters.MessageConverter;
import org.reactivecommons.async.commons.converters.json.DefaultObjectMapperSupplier;
import org.reactivecommons.async.commons.converters.json.ObjectMapperSupplier;
import org.reactivecommons.async.commons.ext.CustomReporter;
import org.reactivecommons.async.rabbit.DynamicRegistryImp;
import org.reactivecommons.async.rabbit.HandlerResolver;
import org.reactivecommons.async.rabbit.RabbitDiscardNotifier;
import org.reactivecommons.async.rabbit.RabbitDomainEventBus;
import org.reactivecommons.async.rabbit.communications.ReactiveMessageListener;
import org.reactivecommons.async.rabbit.communications.ReactiveMessageSender;
import org.reactivecommons.async.rabbit.communications.TopologyCreator;
import org.reactivecommons.async.rabbit.config.props.AsyncProps;
import org.reactivecommons.async.rabbit.config.props.BrokerConfigProps;
import org.reactivecommons.async.rabbit.converters.json.JacksonMessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.PropertyMapper;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
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

import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Level;
import java.util.stream.Stream;

import static org.reactivecommons.async.api.HandlerRegistry.DEFAULT_DOMAIN;
import static reactor.rabbitmq.ExchangeSpecification.exchange;

@Log
@Configuration
@RequiredArgsConstructor
@EnableConfigurationProperties({
        RabbitProperties.class,
        AsyncProps.class
})
@Import({BrokerConfigProps.class, RabbitHealthConfig.class})
public class RabbitMqConfig {

    private static final String LISTENER_TYPE = "listener";

    private static final String SENDER_TYPE = "sender";

    private final AsyncProps asyncProps;

    @Value("${spring.application.name}")
    private String appName;

    @Bean
    public ReactiveMessageSender messageSender(MessageConverter converter, BrokerConfigProps brokerConfigProps, SenderOptions senderOptions) {
        final Sender sender = RabbitFlux.createSender(senderOptions);
        return new ReactiveMessageSender(sender, brokerConfigProps.getAppName(), converter, new TopologyCreator(sender));
    }

    @Bean
    public SenderOptions reactiveCommonsSenderOptions(ConnectionFactoryProvider provider, RabbitProperties rabbitProperties) {
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

    @Bean
    public ReactiveMessageListener messageListener(ConnectionFactoryProvider provider) {
        final Mono<Connection> connection =
                createConnectionMono(provider.getConnectionFactory(), appName, LISTENER_TYPE);
        final Receiver receiver = RabbitFlux.createReceiver(new ReceiverOptions().connectionMono(connection));
        final Sender sender = RabbitFlux.createSender(new SenderOptions().connectionMono(connection));

        return new ReactiveMessageListener(receiver,
                new TopologyCreator(sender),
                asyncProps.getFlux().getMaxConcurrency(),
                asyncProps.getPrefetchCount());
    }

    @Bean
    @ConditionalOnMissingBean
    public BrokerConfig brokerConfig() {
        return new BrokerConfig();
    }

    @Bean
    @ConditionalOnMissingBean
    public ConnectionFactoryProvider rabbitRConnectionFactory(RabbitProperties properties)
            throws NoSuchAlgorithmException, KeyManagementException {
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

    @Bean
    @ConditionalOnMissingBean
    public ObjectMapperSupplier objectMapperSupplier() {
        return new DefaultObjectMapperSupplier();
    }

    @Bean
    @ConditionalOnMissingBean
    public MessageConverter messageConverter(ObjectMapperSupplier objectMapperSupplier) {
        return new JacksonMessageConverter(objectMapperSupplier.get());
    }

    @Bean
    @ConditionalOnMissingBean
    public DiscardNotifier rabbitDiscardNotifier(ObjectMapperSupplier objectMapperSupplier, AsyncProps asyncProps,
                                                 ReactiveMessageSender sender, BrokerConfigProps props) {
        return new RabbitDiscardNotifier(domainEventBus(sender, props, asyncProps.getCreateTopology()), objectMapperSupplier.get());
    }

    @Bean
    @ConditionalOnMissingBean
    public CustomReporter reactiveCommonsCustomErrorReporter() {
        return new CustomReporter() {
            @Override
            public Mono<Void> reportError(Throwable ex, Message rawMessage, Command<?> message, boolean redelivered) {
                return Mono.empty();
            }

            @Override
            public Mono<Void> reportError(Throwable ex, Message rawMessage, DomainEvent<?> message, boolean redelivered) {
                return Mono.empty();
            }

            @Override
            public Mono<Void> reportError(Throwable ex, Message rawMessage, AsyncQuery<?> message, boolean redelivered) {
                return Mono.empty();
            }
        };
    }

    private DomainEventBus domainEventBus(ReactiveMessageSender sender, BrokerConfigProps props, boolean createExchange) {
        final String exchangeName = props.getDomainEventsExchangeName();
        if (createExchange) {
            sender.getTopologyCreator().declare(exchange(exchangeName).durable(true).type("topic")).subscribe();
        }
        return new RabbitDomainEventBus(sender, exchangeName);
    }

    Mono<Connection> createConnectionMono(ConnectionFactory factory, String connectionPrefix, String connectionType) {
        return Mono.fromCallable(() -> factory.newConnection(connectionPrefix + " " + connectionType))
                .doOnError(err ->
                        log.log(Level.SEVERE, "Error creating connection to RabbitMq Broker. Starting retry process...", err)
                )
                .retryWhen(Retry.backoff(Long.MAX_VALUE, Duration.ofMillis(300))
                        .maxBackoff(Duration.ofMillis(3000)))
                .cache();
    }

    @Bean
    public HandlerResolver resolver(ApplicationContext context, DefaultCommandHandler defaultCommandHandler) {
        final Map<String, HandlerRegistry> registries = context.getBeansOfType(HandlerRegistry.class);

        final ConcurrentMap<String, RegisteredQueryHandler<?, ?>> queryHandlers = registries
                .values().stream()
                .flatMap(r -> r.getHandlers().stream())
                .collect(ConcurrentHashMap::new, (map, handler) -> map.put(handler.getPath(), handler),
                        ConcurrentHashMap::putAll);

        final ConcurrentMap<String, RegisteredEventListener<?>> eventsToBind = registries
                .values().stream()
                .flatMap(r -> r.getDomainEventListeners().get(DEFAULT_DOMAIN).stream())
                .collect(ConcurrentHashMap::new, (map, handler) -> map.put(handler.getPath(), handler),
                        ConcurrentHashMap::putAll);

        // event handlers and dynamic handlers
        final ConcurrentMap<String, RegisteredEventListener<?>> eventHandlers = registries
                .values().stream()
                .flatMap(r -> Stream.concat(r.getDomainEventListeners().get(DEFAULT_DOMAIN).stream(), r.getDynamicEventHandlers().stream()))
                .collect(ConcurrentHashMap::new, (map, handler) -> map.put(handler.getPath(), handler),
                        ConcurrentHashMap::putAll);

        final ConcurrentMap<String, RegisteredCommandHandler<?>> commandHandlers = registries
                .values().stream()
                .flatMap(r -> r.getCommandHandlers().stream())
                .collect(ConcurrentHashMap::new, (map, handler) -> map.put(handler.getPath(), handler),
                        ConcurrentHashMap::putAll);

        final ConcurrentMap<String, RegisteredEventListener<?>> eventNotificationListener = registries
                .values()
                .stream()
                .flatMap(r -> r.getEventNotificationListener().stream())
                .collect(ConcurrentHashMap::new, (map, handler) -> map.put(handler.getPath(), handler),
                        ConcurrentHashMap::putAll);

        return new HandlerResolver(queryHandlers, eventHandlers, eventsToBind, eventNotificationListener, commandHandlers) {
            @Override
            @SuppressWarnings("unchecked")
            public <T> RegisteredCommandHandler<T> getCommandHandler(String path) {
                final RegisteredCommandHandler<T> handler = super.getCommandHandler(path);
                return handler != null ? handler : new RegisteredCommandHandler<>("", defaultCommandHandler, Object.class);
            }
        };
    }

    @Bean
    public DynamicRegistry dynamicRegistry(HandlerResolver resolver, ReactiveMessageListener listener, IBrokerConfigProps props) {
        return new DynamicRegistryImp(resolver, listener.getTopologyCreator(), props);
    }

    @Bean
    @ConditionalOnMissingBean
    public DefaultQueryHandler defaultHandler() {
        return (DefaultQueryHandler<Object, Object>) command ->
                Mono.error(new RuntimeException("No Handler Registered"));
    }

    @Bean
    @ConditionalOnMissingBean
    public DefaultCommandHandler defaultCommandHandler() {
        return message -> Mono.error(new RuntimeException("No Handler Registered"));
    }

}
