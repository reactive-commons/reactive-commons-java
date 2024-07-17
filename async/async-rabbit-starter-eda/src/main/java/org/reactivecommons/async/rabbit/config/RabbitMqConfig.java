package org.reactivecommons.async.rabbit.config;

import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.java.Log;
import org.reactivecommons.api.domain.Command;
import org.reactivecommons.api.domain.DomainEvent;
import org.reactivecommons.api.domain.DomainEventBus;
import org.reactivecommons.async.api.AsyncQuery;
import org.reactivecommons.async.api.DefaultCommandHandler;
import org.reactivecommons.async.api.DefaultQueryHandler;
import org.reactivecommons.async.api.DynamicRegistry;
import org.reactivecommons.async.api.HandlerRegistry;
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
import org.reactivecommons.async.rabbit.config.props.AsyncPropsDomain;
import org.reactivecommons.async.rabbit.config.props.AsyncPropsDomainProperties;
import org.reactivecommons.async.rabbit.config.props.BrokerConfigProps;
import org.reactivecommons.async.rabbit.converters.json.JacksonCloudEventMessageConverter;
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

import java.time.Duration;
import java.util.Map;
import java.util.logging.Level;

import static org.reactivecommons.async.api.HandlerRegistry.DEFAULT_DOMAIN;

@Log
@Configuration
@RequiredArgsConstructor
@EnableConfigurationProperties({RabbitProperties.class, AsyncPropsDomainProperties.class})
@Import({RabbitHealthConfig.class, AsyncPropsDomain.class})
public class RabbitMqConfig {

    private static final String LISTENER_TYPE = "listener";

    private static final String SENDER_TYPE = "sender";


    @Bean
    public ConnectionManager buildConnectionManager(AsyncPropsDomain props, MessageConverter converter,
                                                    BrokerConfig brokerConfig, ObjectMapperSupplier objectMapperSupplier) {
        ConnectionManager connectionManager = new ConnectionManager();
        props.forEach((domain, properties) -> {
            ConnectionFactoryProvider provider = createConnectionFactoryProvider(properties.getConnectionProperties());
            ReactiveMessageSender sender = createMessageSender(provider, properties, converter);
            ReactiveMessageListener listener = createMessageListener(provider, properties);
            connectionManager.addDomain(domain, listener, sender, provider);
            
            ReactiveMessageSender appDomainSender = connectionManager.getSender(domain);
            DomainEventBus appDomainEventBus = new RabbitDomainEventBus(appDomainSender, props.getProps(domain)
                    .getBrokerConfigProps().getDomainEventsExchangeName(), brokerConfig);
            DiscardNotifier notifier = new RabbitDiscardNotifier(appDomainEventBus, objectMapperSupplier.get());
            connectionManager.setDiscardNotifier(domain, notifier);
        });
        return connectionManager;
    }

    @Bean
    public DomainHandlers buildHandlers(AsyncPropsDomain props, ApplicationContext context,
                                        HandlerRegistry primaryRegistry, DefaultCommandHandler<?> commandHandler) {
        DomainHandlers handlers = new DomainHandlers();
        final Map<String, HandlerRegistry> registries = context.getBeansOfType(HandlerRegistry.class);
        if (!registries.containsValue(primaryRegistry)) {
            registries.put("primaryHandlerRegistry", primaryRegistry);
        }
        props.forEach((domain, properties) -> {
            HandlerResolver resolver = HandlerResolverBuilder.buildResolver(domain, registries, commandHandler);
            handlers.add(domain, resolver);
        });
        return handlers;
    }


    private ReactiveMessageSender createMessageSender(ConnectionFactoryProvider provider,
                                                      AsyncProps props,
                                                      MessageConverter converter) {
        final Sender sender = RabbitFlux.createSender(reactiveCommonsSenderOptions(props.getAppName(), provider,
                props.getConnectionProperties()));
        return new ReactiveMessageSender(sender, props.getAppName(), converter, new TopologyCreator(sender));
    }

    private SenderOptions reactiveCommonsSenderOptions(String appName, ConnectionFactoryProvider provider, RabbitProperties rabbitProperties) {
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

    public ReactiveMessageListener createMessageListener(ConnectionFactoryProvider provider, AsyncProps props) {
        final Mono<Connection> connection =
                createConnectionMono(provider.getConnectionFactory(), props.getAppName(), LISTENER_TYPE);
        final Receiver receiver = RabbitFlux.createReceiver(new ReceiverOptions().connectionMono(connection));
        final Sender sender = RabbitFlux.createSender(new SenderOptions().connectionMono(connection));

        return new ReactiveMessageListener(receiver,
                new TopologyCreator(sender),
                props.getFlux().getMaxConcurrency(),
                props.getPrefetchCount());
    }

    @SneakyThrows
    public ConnectionFactoryProvider createConnectionFactoryProvider(RabbitProperties properties) {
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
    public BrokerConfig brokerConfig() {
        return new BrokerConfig();
    }

    @Bean
    @ConditionalOnMissingBean
    public ObjectMapperSupplier objectMapperSupplier() {
        return new DefaultObjectMapperSupplier();
    }

    @Bean
    @ConditionalOnMissingBean
    public MessageConverter messageConverter(ObjectMapperSupplier objectMapperSupplier) {
        return new JacksonCloudEventMessageConverter(objectMapperSupplier.get());
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
    public DynamicRegistry dynamicRegistry(ConnectionManager connectionManager, AsyncPropsDomain asyncPropsDomain,
                                           DomainHandlers handlers) {
        IBrokerConfigProps brokerConfigProps = new BrokerConfigProps(asyncPropsDomain.getProps(DEFAULT_DOMAIN));
        return new DynamicRegistryImp(handlers.get(DEFAULT_DOMAIN),
                connectionManager.getListener(DEFAULT_DOMAIN).getTopologyCreator(), brokerConfigProps);
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

    @Bean
    @ConditionalOnMissingBean(HandlerRegistry.class)
    public HandlerRegistry defaultHandlerRegistry() {
        return HandlerRegistry.register();
    }

    @Bean
    @ConditionalOnMissingBean(AsyncPropsDomain.SecretFiller.class)
    public AsyncPropsDomain.SecretFiller defaultSecretFiller() {
        return ignored -> {
        };
    }

}
