package org.reactivecommons.async.rabbit;

import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import io.micrometer.core.instrument.MeterRegistry;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.IoHandlerFactory;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.reactivecommons.api.domain.DomainEventBus;
import org.reactivecommons.async.commons.DLQDiscardNotifier;
import org.reactivecommons.async.commons.DiscardNotifier;
import org.reactivecommons.async.commons.config.BrokerConfig;
import org.reactivecommons.async.commons.converters.MessageConverter;
import org.reactivecommons.async.rabbit.communications.ConnectionBlockedState;
import org.reactivecommons.async.rabbit.communications.ReactiveMessageListener;
import org.reactivecommons.async.rabbit.communications.ReactiveMessageSender;
import org.reactivecommons.async.rabbit.communications.SenderSettings;
import org.reactivecommons.async.rabbit.communications.TopologyCreator;
import org.reactivecommons.async.rabbit.communications.UnroutableMessageNotifier;
import org.reactivecommons.async.rabbit.config.ConnectionFactoryProvider;
import org.reactivecommons.async.rabbit.config.RabbitProperties;
import org.reactivecommons.async.rabbit.config.props.AsyncProps;
import org.reactivecommons.async.rabbit.config.spring.RabbitPropertiesBase;
import org.springframework.boot.context.properties.PropertyMapper;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.rabbitmq.ChannelPool;
import reactor.rabbitmq.ChannelPoolFactory;
import reactor.rabbitmq.ChannelPoolOptions;
import reactor.rabbitmq.Receiver;
import reactor.rabbitmq.ReceiverOptions;
import reactor.rabbitmq.Sender;
import reactor.rabbitmq.SenderOptions;
import reactor.rabbitmq.Utils;
import reactor.util.retry.Retry;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.TrustManagerFactory;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class RabbitMQSetupUtils {
    public static final int START_INTERVAL = 300;
    public static final int MAX_BACKOFF_INTERVAL = 3000;
    private static final int CONNECTION_CLOSE_TIMEOUT_MILLIS = 5_000;

    private static final ConcurrentMap<AsyncProps, ConnectionFactory> FACTORY_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentMap<ConnectionKey, Mono<Connection>> CONNECTION_CACHE = new ConcurrentHashMap<>();
    /**
     * The connections of each factory once they have been established, to inspect them without waiting for them.
     */
    private static final ConcurrentMap<ConnectionKey, Connection> ESTABLISHED_CONNECTIONS = new ConcurrentHashMap<>();
    /**
     * Tracks the {@code connection.blocked} / {@code connection.unblocked} state of the publishing connection of each
     * factory, so the health indicator and the sender can observe broker-side back-pressure.
     */
    private static final ConcurrentMap<ConnectionFactory, ConnectionBlockedState> CONNECTION_BLOCKED_STATES =
            new ConcurrentHashMap<>();
    /**
     * The live broker providers using the connections of each factory (guarded by EVENT_LOOP_GROUP_LOCK): the
     * connections of a factory are closed once none uses them, e.g. when its application context closes while
     * another one keeps running in the same JVM.
     */
    private static final Map<ConnectionFactory, Integer> CONNECTION_USERS = new HashMap<>();

    /**
     * The Netty event loop group shared by every RabbitMQ connection. It is shut down when the last application
     * context using it closes, and re-created when a new one needs it (e.g. tests creating several contexts in the
     * same JVM).
     */
    private static final Object EVENT_LOOP_GROUP_LOCK = new Object();
    private static EventLoopGroup sharedEventLoopGroup; // guarded by EVENT_LOOP_GROUP_LOCK
    private static int eventLoopGroupUsers; // guarded by EVENT_LOOP_GROUP_LOCK

    /**
     * Each factory gets one connection per role. The broker blocks a connection that publishes during a resource
     * alarm and stops reading from it: consuming on its own connection keeps acknowledgements, cancellations and
     * topology declarations of the listeners working while the publishers are blocked.
     *
     * @see <a href="https://www.rabbitmq.com/docs/alarms">Memory and Disk Alarms</a>
     */
    enum ConnectionRole {
        SENDER("sender"),
        LISTENER("listener");

        private final String kind;

        ConnectionRole(String kind) {
            this.kind = kind;
        }
    }

    private record ConnectionKey(ConnectionFactory factory, ConnectionRole role) {
    }

    private static EventLoopGroup createEventLoopGroup() {
        log.debug("Creating shared Netty EventLoopGroup with default thread configuration for RabbitMQ connections");
        IoHandlerFactory ioHandlerFactory = NioIoHandler.newFactory();
        return new MultiThreadIoEventLoopGroup(ioHandlerFactory);
    }

    private static EventLoopGroup sharedEventLoopGroup() {
        synchronized (EVENT_LOOP_GROUP_LOCK) {
            if (sharedEventLoopGroup == null || sharedEventLoopGroup.isShuttingDown()) {
                sharedEventLoopGroup = createEventLoopGroup();
            }
            return sharedEventLoopGroup;
        }
    }

    public static ConnectionFactoryProvider connectionFactoryProvider(AsyncProps asyncProps,
                                                                      ConnectionFactoryCustomizer cfCustomizer) {
        final ConnectionFactory factory = FACTORY_CACHE.computeIfAbsent(asyncProps, props -> {
            try {
                RabbitProperties rabbitProperties = props.getConnectionProperties();
                ConnectionFactory newFactory = new ConnectionFactory();
                PropertyMapper map = PropertyMapper.get();
                map.from(rabbitProperties::determineHost).to(newFactory::setHost);
                map.from(rabbitProperties::determinePort).to(newFactory::setPort);
                map.from(rabbitProperties::determineUsername).to(newFactory::setUsername);
                map.from(rabbitProperties::determinePassword).to(newFactory::setPassword);
                map.from(rabbitProperties::determineVirtualHost).to(newFactory::setVirtualHost);
                map.from(rabbitProperties::getConnectionTimeout).asInt(Duration::toMillis)
                        .to(newFactory::setConnectionTimeout);
                map.from(rabbitProperties::getRequestedHeartbeat).asInt(Duration::getSeconds)
                        .to(newFactory::setRequestedHeartbeat);
                newFactory.netty().eventLoopGroup(sharedEventLoopGroup());

                setUpSSL(newFactory, rabbitProperties);
                return cfCustomizer.customize(newFactory, props);
            } catch (Exception e) {
                throw new RuntimeException("Error creating ConnectionFactory: ", e);
            }
        });
        return () -> factory;
    }

    public static ReactiveMessageSender createMessageSender(ConnectionFactoryProvider provider,
                                                            AsyncProps props,
                                                            MessageConverter converter,
                                                            UnroutableMessageNotifier unroutableMessageNotifier) {
        return createMessageSender(provider, props, converter, unroutableMessageNotifier, null, null);
    }

    public static ReactiveMessageSender createMessageSender(ConnectionFactoryProvider provider,
                                                            AsyncProps props,
                                                            MessageConverter converter,
                                                            UnroutableMessageNotifier unroutableMessageNotifier,
                                                            MeterRegistry meterRegistry,
                                                            String domain) {
        final Sender sender = new Sender(reactiveCommonsSenderOptions(props.getAppName(), provider,
                props.getConnectionProperties()));
        final TopologyCreator topologyCreator = new TopologyCreator(sender, props.getQueueType());
        final Duration sendTimeout = props.getSendTimeout() != null
                ? props.getSendTimeout()
                : Duration.ofSeconds(10);
        final ConnectionBlockedState blockedState = blockedState(provider.getConnectionFactory());
        return new ReactiveMessageSender(sender, props.getAppName(), converter, topologyCreator,
                unroutableMessageNotifier, new SenderSettings(props.getMandatory(), sendTimeout,
                props.getHandOverPoolSize(), props.getHandOverQueueCapacity(), props.getMaxMessageSize(),
                blockedState, meterRegistry, domain));
    }

    public static ReactiveMessageListener createMessageListener(ConnectionFactoryProvider provider, AsyncProps props,
                                                                MeterRegistry meterRegistry, String domain) {
        final Mono<Connection> connection = createConnectionMono(provider.getConnectionFactory(), props.getAppName(),
                ConnectionRole.LISTENER);
        final Receiver receiver = new Receiver(new ReceiverOptions().connectionMono(connection));
        final Sender sender = new Sender(new SenderOptions().connectionMono(connection));

        return new ReactiveMessageListener(receiver,
                new TopologyCreator(sender, props.getQueueType()),
                props.getFlux().getMaxConcurrency(),
                props.getPrefetchCount(),
                new org.reactivecommons.async.rabbit.listeners.SubscriberRegistry(),
                meterRegistry,
                domain,
                props.getHandlerTimeout());
    }

    public static TopologyCreator createTopologyCreator(AsyncProps props, ConnectionFactoryCustomizer cfCustomizer) {
        ConnectionFactoryProvider provider = connectionFactoryProvider(props, cfCustomizer);
        // only declares the topology of the listeners: it must keep working while the publishers are blocked
        final Mono<Connection> connection = createConnectionMono(provider.getConnectionFactory(), props.getAppName(),
                ConnectionRole.LISTENER);
        final Sender sender = new Sender(new SenderOptions().connectionMono(connection));
        return new TopologyCreator(sender, props.getQueueType());
    }

    public static DiscardNotifier createDiscardNotifier(ReactiveMessageSender sender, AsyncProps props,
                                                        BrokerConfig brokerConfig, MessageConverter converter) {
        DomainEventBus appDomainEventBus = new RabbitDomainEventBus(sender,
                props.getBrokerConfigProps().getDomainEventsExchangeName(), brokerConfig);
        return new DLQDiscardNotifier(appDomainEventBus, converter);
    }

    private static SenderOptions reactiveCommonsSenderOptions(String appName, ConnectionFactoryProvider provider,
                                                              RabbitProperties rabbitProperties) {
        final Mono<Connection> senderConnection = createConnectionMono(provider.getConnectionFactory(), appName,
                ConnectionRole.SENDER);
        final ChannelPoolOptions channelPoolOptions = new ChannelPoolOptions();
        final PropertyMapper map = PropertyMapper.get();

        map.from(rabbitProperties.getCache().getChannel()::getSize).to(channelPoolOptions::maxCacheSize);

        final ChannelPool channelPool = ChannelPoolFactory.createChannelPool(
                senderConnection,
                channelPoolOptions
        );

        return new SenderOptions()
                .channelPool(channelPool)
                .connectionMono(senderConnection);
    }

    /**
     * The cached Mono of the connection the listeners of the application use, shared with the queue-depth poller so
     * it never opens a connection of its own.
     */
    static Mono<Connection> listenerConnectionMono(ConnectionFactoryProvider provider, String appName) {
        return createConnectionMono(provider.getConnectionFactory(), appName, ConnectionRole.LISTENER);
    }

    private static Mono<Connection> createConnectionMono(ConnectionFactory factory, String appName,
                                                         ConnectionRole role) {
        return CONNECTION_CACHE.computeIfAbsent(new ConnectionKey(factory, role), key -> {
            final ConnectionFactory f = key.factory();
            log.info("Creating {} connection mono to RabbitMQ Broker in host '{}'", role.kind, f.getHost());
            final AtomicInteger failedAttempts = new AtomicInteger();
            return Mono.fromCallable(() -> f.newConnection(
                            appName + "-" + InstanceIdentifier.getInstanceId(role.kind, "")
                    ))
                    // opening a connection blocks (up to the connection timeout): neither on the thread that
                    // subscribes nor, for the retries, on the non-blocking parallel thread of the backoff
                    .subscribeOn(Schedulers.boundedElastic())
                    .doOnError(err -> logConnectionFailure(f, role, failedAttempts.incrementAndGet(), err))
                    .retryWhen(Retry.backoff(Long.MAX_VALUE, Duration.ofMillis(START_INTERVAL))
                            .maxBackoff(Duration.ofMillis(MAX_BACKOFF_INTERVAL)))
                    .doOnNext(connection -> {
                        final int failed = failedAttempts.getAndSet(0);
                        if (failed > 0) {
                            log.info("{} connection to RabbitMQ Broker in host '{}' established after {} failed "
                                    + "attempts", role.kind, f.getHost(), failed);
                        }
                        ESTABLISHED_CONNECTIONS.put(key, connection);
                        if (role == ConnectionRole.SENDER) {
                            final ConnectionBlockedState blockedState = blockedState(f);
                            connection.addBlockedListener(blockedState::onBlocked, blockedState::onUnblocked);
                            // kept across recoveries, like the blocked listener: a closed connection is not blocked
                            connection.addShutdownListener(cause -> blockedState.onConnectionShutdown());
                        }
                    })
                    // a connection the client will not re-open is replaced by a new one the next time it is used (e.g.
                    // when a listener restarts or a publishing flow is re-subscribed), unless the application released
                    // its connections meanwhile
                    .cacheInvalidateIf(connection -> CONNECTION_CACHE.containsKey(key)
                            && closedForGood(connection, role));
        });
    }

    /**
     * The attempts are retried every few seconds for as long as the broker refuses the connection: only the first
     * failure is logged as an error, with its stack trace, the next ones at debug level.
     */
    private static void logConnectionFailure(ConnectionFactory factory, ConnectionRole role, int attempt,
                                             Throwable error) {
        if (attempt == 1) {
            log.error("Error creating connection to RabbitMQ Broker in host '{}'. Starting retry process...",
                    factory.getHost(), error);
        } else {
            log.debug("Error creating {} connection to RabbitMQ Broker in host '{}' (attempt {}), retrying: {}",
                    role.kind, factory.getHost(), attempt, error.toString());
        }
    }

    /**
     * Whether the connection is closed and the client will not re-open it (see {@link Utils#isClosedForGood}), e.g.
     * because a {@link ConnectionFactoryCustomizer} disabled its automatic recovery.
     */
    private static boolean closedForGood(Connection connection, ConnectionRole role) {
        final boolean closedForGood = Utils.isClosedForGood(connection);
        if (closedForGood) {
            log.warn("The {} connection to RabbitMQ is closed and will not be recovered ({}): opening a new one",
                    role.kind, connection.getCloseReason() != null ? connection.getCloseReason().getMessage()
                            : "unknown reason");
        }
        return closedForGood;
    }

    /**
     * Returns the publishing connection created for the given factory, if it has already been established. It never
     * opens or waits for a connection, so it is safe to call from a health check.
     *
     * @param factory the connection factory of a domain
     * @return the established publishing connection, or empty if it has not been established yet
     */
    public static Optional<Connection> establishedConnection(ConnectionFactory factory) {
        return Optional.ofNullable(ESTABLISHED_CONNECTIONS.get(new ConnectionKey(factory, ConnectionRole.SENDER)));
    }

    /**
     * Returns the consuming connection created for the given factory, if it has already been established. It stays
     * empty for an application that never listens. Like {@link #establishedConnection}, it never opens or waits.
     *
     * @param factory the connection factory of a domain
     * @return the established consuming connection, or empty if it has not been established (yet)
     */
    public static Optional<Connection> establishedListenerConnection(ConnectionFactory factory) {
        return Optional.ofNullable(ESTABLISHED_CONNECTIONS.get(new ConnectionKey(factory, ConnectionRole.LISTENER)));
    }

    /**
     * Returns the shared {@link ConnectionBlockedState} tracking {@code connection.blocked} notifications for the
     * connection associated to the given factory. The state is lazily created on first access, so the sender and
     * the health indicator see the same instance that the connection setup registers the listener on.
     *
     * @param factory the connection factory of a domain
     * @return the shared state, never {@code null}
     */
    public static ConnectionBlockedState blockedState(ConnectionFactory factory) {
        return CONNECTION_BLOCKED_STATES.computeIfAbsent(factory, f -> new ConnectionBlockedState());
    }

    // SSL based on RabbitConnectionFactoryBean
    // https://github.com/spring-projects/spring-amqp/blob/main/spring-rabbit/src/main/java/org/springframework/amqp/rabbit/connection/RabbitConnectionFactoryBean.java
    // Adapted for Netty: https://www.rabbitmq.com/client-libraries/java-api-guide#netty

    private static void setUpSSL(ConnectionFactory factory, RabbitProperties properties)
            throws NoSuchAlgorithmException, KeyStoreException, UnrecoverableKeyException,
            CertificateException, IOException {
        var ssl = properties.getSsl();
        if (ssl != null && ssl.isEnabled()) {
            SslContextBuilder sslContextBuilder = SslContextBuilder.forClient();

            // Configure TrustManager
            var trustManagerFactory = configureTrustManagerFactory(ssl);
            sslContextBuilder.trustManager(trustManagerFactory);

            // Configure KeyManager if keystore is provided
            if (ssl.getKeyStore() != null) {
                var keyManagerFactory = configureKeyManagerFactory(ssl);
                sslContextBuilder.keyManager(keyManagerFactory);
            }

            // Set SSL protocol if specified
            if (ssl.getAlgorithm() != null) {
                log.info("Using SSL protocol: {}", ssl.getAlgorithm());
                sslContextBuilder.protocols(ssl.getAlgorithm());
            }

            SslContext sslContext = sslContextBuilder.build();
            factory.netty().sslContext(sslContext);

            if (ssl.isVerifyHostname()) {
                factory.enableHostnameVerification();
            }
        }
    }

    private static KeyManagerFactory configureKeyManagerFactory(RabbitPropertiesBase.Ssl ssl)
            throws KeyStoreException, IOException, NoSuchAlgorithmException,
            CertificateException, UnrecoverableKeyException {
        var ks = KeyStore.getInstance(ssl.getKeyStoreType());
        char[] keyPassphrase = null;
        if (ssl.getKeyStorePassword() != null) {
            keyPassphrase = ssl.getKeyStorePassword().toCharArray();
        }
        try (var inputStream = new FileInputStream(ssl.getKeyStore())) {
            ks.load(inputStream, keyPassphrase);
        }
        var kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, keyPassphrase);
        return kmf;
    }

    private static TrustManagerFactory configureTrustManagerFactory(RabbitPropertiesBase.Ssl ssl)
            throws KeyStoreException, IOException, NoSuchAlgorithmException, CertificateException {
        KeyStore tks = null;
        if (ssl.getTrustStore() != null) {
            tks = KeyStore.getInstance(ssl.getTrustStoreType());
            char[] trustPassphrase = null;
            if (ssl.getTrustStorePassword() != null) {
                trustPassphrase = ssl.getTrustStorePassword().toCharArray();
            }
            try (InputStream inputStream = new FileInputStream(ssl.getTrustStore())) {
                tks.load(inputStream, trustPassphrase);
            }
        }

        var tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(tks);
        return tmf;
    }

    /**
     * Registers a user (a broker provider) of the connections of the factory.
     */
    public static void retainConnections(ConnectionFactory factory) {
        synchronized (EVENT_LOOP_GROUP_LOCK) {
            CONNECTION_USERS.merge(factory, 1, Integer::sum);
        }
    }

    /**
     * Unregisters a user of the connections of the factory. Once none is left, its connections are closed and the
     * factory is forgotten: an application created later with the same properties gets new ones.
     */
    public static void releaseConnections(ConnectionFactory factory) {
        synchronized (EVENT_LOOP_GROUP_LOCK) {
            final Integer users = CONNECTION_USERS.get(factory);
            if (users == null) {
                return; // not retained
            }
            if (users > 1) {
                CONNECTION_USERS.put(factory, users - 1);
                return;
            }
            CONNECTION_USERS.remove(factory);
            for (ConnectionRole role : ConnectionRole.values()) {
                final ConnectionKey key = new ConnectionKey(factory, role);
                CONNECTION_CACHE.remove(key);
                final Connection connection = ESTABLISHED_CONNECTIONS.remove(key);
                if (connection != null) {
                    closeConnection(connection);
                }
            }
            FACTORY_CACHE.values().removeIf(factory::equals);
            CONNECTION_BLOCKED_STATES.remove(factory);
        }
    }

    /**
     * Registers an application context that uses the shared event loop group.
     */
    public static void retainEventLoopGroup() {
        synchronized (EVENT_LOOP_GROUP_LOCK) {
            eventLoopGroupUsers++;
        }
    }

    /**
     * Unregisters an application context. When the last one is gone, the cached connections are closed, the cached
     * factories and connections are forgotten and the shared event loop group is shut down; the next context creates
     * new ones.
     */
    public static void releaseEventLoopGroup() {
        synchronized (EVENT_LOOP_GROUP_LOCK) {
            if (eventLoopGroupUsers > 0) {
                eventLoopGroupUsers--;
            }
            if (eventLoopGroupUsers > 0) {
                log.info("Shared Netty EventLoopGroup still used by {} application context(s)", eventLoopGroupUsers);
                return;
            }
            ESTABLISHED_CONNECTIONS.values().forEach(RabbitMQSetupUtils::closeConnection);
            CONNECTION_USERS.clear();
            FACTORY_CACHE.clear();
            CONNECTION_CACHE.clear();
            ESTABLISHED_CONNECTIONS.clear();
            CONNECTION_BLOCKED_STATES.clear();
            shutdownEventLoopGroup();
        }
    }

    /**
     * Closes the connection with the AMQP handshake, so the broker does not see its socket cut when the event loop
     * group shuts down. Forced closed after {@link #CONNECTION_CLOSE_TIMEOUT_MILLIS}, e.g. if the broker blocks it.
     */
    private static void closeConnection(Connection connection) {
        if (!connection.isOpen()) {
            // e.g. being recovered after a network failure: only closing or aborting it stops the recovery, which
            // would reconnect it (with the channels and consumers of a closed application) once the broker is back
            connection.abort(CONNECTION_CLOSE_TIMEOUT_MILLIS);
            return;
        }
        try {
            connection.close(CONNECTION_CLOSE_TIMEOUT_MILLIS);
        } catch (Exception e) {
            log.warn("Error closing RabbitMQ connection {}", connection.getClientProvidedName(), e);
        }
    }

    public static void shutdownEventLoopGroup() {
        synchronized (EVENT_LOOP_GROUP_LOCK) {
            if (sharedEventLoopGroup != null && !sharedEventLoopGroup.isShuttingDown()) {
                log.info("Shutting down shared Netty EventLoopGroup for RabbitMQ connections");
                sharedEventLoopGroup.shutdownGracefully();
            } else {
                log.info("Shared Netty EventLoopGroup is already shutting down or shutdown");
            }
        }
    }

}
